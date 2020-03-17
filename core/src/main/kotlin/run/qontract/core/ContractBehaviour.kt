package run.qontract.core

import io.cucumber.gherkin.GherkinDocumentBuilder
import io.cucumber.gherkin.Parser
import io.cucumber.messages.IdGenerator
import io.cucumber.messages.IdGenerator.Incrementing
import io.cucumber.messages.Messages.GherkinDocument
import run.qontract.core.pattern.*
import run.qontract.core.pattern.PatternTable.Companion.examplesFrom
import run.qontract.core.pattern.PatternTable.Companion.fromPSV
import run.qontract.core.utilities.jsonStringToMap
import run.qontract.mock.NoMatchingScenario
import run.qontract.test.TestExecutor
import java.net.URI
import java.util.*

class ContractBehaviour(contractGherkinDocument: GherkinDocument) {
    private val scenarios: List<Scenario> = lex(contractGherkinDocument)
    private var serverState = HashMap<String, Any>()

    constructor(gherkinData: String) : this(parseGherkinString(gherkinData))

    @Throws(Exception::class)
    fun lookup(httpRequest: HttpRequest): HttpResponse {
        try {
            val results = Results()
            scenarios.find {
                it.matches(httpRequest, serverState).also { result ->
                    results.add(result, httpRequest, null)
                } is Result.Success
            }?.let {
                return it.generateHttpResponse(serverState)
            }
            return results.generateErrorHttpResponse()
        } finally {
            serverState = HashMap()
        }
    }

    fun executeTests(suggestions: List<Scenario>, testExecutorFn: TestExecutor): ExecutionInfo {
        val scenariosWithSuggestions = generateTestScenarios(suggestions)
        val executionInfo = ExecutionInfo()
        for (scenario in scenariosWithSuggestions) {
            executeTest(testExecutorFn, scenario.generateTestScenarios(), executionInfo)
        }
        return executionInfo
    }

    fun executeTests(testExecutorFn: TestExecutor): ExecutionInfo {
        val executionInfo = ExecutionInfo()
        for (scenario in scenarios) {
            executeTest(testExecutorFn, scenario.generateTestScenarios(), executionInfo)
        }
        return executionInfo
    }

    private fun executeTest(testExecutor: TestExecutor, testScenarios: List<Scenario>, executionInfo: ExecutionInfo) {
        for (testScenario in testScenarios) {
            executeTest(testExecutor, testScenario, executionInfo)
        }
    }

    private fun executeTest(testExecutor: TestExecutor, scenario: Scenario, executionInfo: ExecutionInfo) {
        testExecutor.setServerState(scenario.serverState)
        val request = scenario.generateHttpRequest()
        try {
            val response = testExecutor.execute(request)
            scenario.matches(response).record(executionInfo, request, response)
        } catch (exception: Throwable) {
            Result.Failure("Error: ${exception.message}")
                    .also { it.updateScenario(scenario) }
                    .record(executionInfo, request, null)
        }
    }

    fun setServerState(serverState: Map<String, Any>) {
        this.serverState.putAll(serverState)
    }

    fun matches(request: HttpRequest, response: HttpResponse): Boolean {
        return scenarios.filter { it.matches(request, serverState) is Result.Success }
                .none { it.matches(response) is Result.Success }
    }

    fun getResponseForMock(request: HttpRequest, response: HttpResponse): HttpResponse {
        try {
            val results = Results()

            for (scenario in scenarios) {
                when (val requestMatches = scenario.matches(request, serverState)) {
                    is Result.Success -> {
                        when (val responseMatches = scenario.matchesMock(response)) {
                            is Result.Success -> return scenario.generateHttpResponseFrom(response)
                            is Result.Failure -> {
                                responseMatches.add("Response didn't match the pattern")
                                        .also { failure -> failure.updateScenario(scenario) }
                                results.add(responseMatches, request, response)
                            }
                        }
                    }
                    is Result.Failure -> {
                        requestMatches.add("Request didn't match the pattern")
                                .also { failure -> failure.updateScenario(scenario) }
                        results.add(requestMatches, request, response)
                    }
                }
            }

            throw NoMatchingScenario(results.generateErrorMessage())
        } finally {
            serverState = HashMap()
        }
    }

    fun generateTestScenarios(suggestions: List<Scenario>) =
            scenarios.map { scenario ->
                scenario.newBasedOn(suggestions)
            }.flatMap { it.generateTestScenarios() }.toList()

}

private fun storeFixture(fixtures: MutableMap<String, Any>, name: String, info: Any) = fixtures.plus(name to info).toMutableMap()

private fun storeFixture(fixtures: MutableMap<String, Any>, rest: String): MutableMap<String, Any> {
    val fixtureTokens = rest.trim().split("\\s+".toRegex(), 2)

    if (fixtureTokens.size < 2)
        throw ContractParseException("Couldn't parse fixture data: $rest")

    return storeFixture(fixtures, fixtureTokens[0], toFixtureData(fixtureTokens[1]))
}

private fun toFixtureData(rawData: String): Any = parsedJSON(rawData)?.value ?: rawData
private fun storePattern(patterns: MutableMap<String, Pattern>, rest: String, rowsList: List<GherkinDocument.Feature.TableRow>): MutableMap<String, Pattern> {
    val tokens = breakIntoParts(rest, 2)

    val patternName = nameToPatternSpec(tokens[0])
    val patternSpec = tokens.getOrElse(1) { "" }.trim()

    val pattern = when {
        patternSpec.isEmpty() -> rowsToPattern(rowsList)
        else -> parsedPattern(patternSpec)
    }

    return patterns.plus(patternName to pattern).toMutableMap()
}

private fun toFacts(rest: String, lookupTable: MutableMap<String, Any>): MutableMap<String, Any> {
    val facts = HashMap<String, Any>()

    try {
        facts.putAll(jsonStringToMap(rest).mapValues { it.value ?: "" })
    } catch (notValidJSON: Exception) {
        val factTokens = breakIntoParts(rest, 2)
        val name = factTokens[0]
        val data = factTokens.getOrNull(1)

        facts[name] = data?.let { convertStringToCorrectType(it) } ?: lookupTable.getOrDefault(name, true)
    }

    return facts
}

private fun lexScenario(steps: MutableList<GherkinDocument.Feature.Step>, examplesList: List<GherkinDocument.Feature.Scenario.Examples>, scenarioInfo: ScenarioInfo): ScenarioInfo {
    val filteredSteps = steps.map { StepInfo(it.text, it.dataTable.rowsList) }.filterNot { it.isEmpty }

    val parsedScenarioInfo = filteredSteps.fold(scenarioInfo) { acc, step ->
        when(step.keyword) {
            in HTTP_METHODS -> {
                step.words.getOrNull(1)?.let {
                    acc.copy(
                            httpRequestPattern = acc.httpRequestPattern.copy(
                                    urlMatcher = URLMatcher(URI.create(step.rest)),
                                    method = step.keyword.toUpperCase()))
                } ?: throw ContractParseException("Line ${step.line}: $step.text")
            }
            "REQUEST-HEADER" -> acc.copy(
                    httpRequestPattern = acc.httpRequestPattern.copy(
                            headersPattern = addToHeaderPattern(step.rest, acc.httpRequestPattern.headersPattern)))
            "RESPONSE-HEADER" -> acc.copy(
                    httpResponsePattern = acc.httpResponsePattern.copy(
                            headersPattern = addToHeaderPattern(step.rest, acc.httpResponsePattern.headersPattern)))
            "STATUS" -> acc.copy(httpResponsePattern = acc.httpResponsePattern.copy(status = Integer.valueOf(step.rest)))
            "REQUEST-BODY" -> acc.copy(httpRequestPattern = acc.httpRequestPattern.bodyPattern(step.rest))
            "RESPONSE-BODY" -> acc.copy(httpResponsePattern = acc.httpResponsePattern.bodyPattern(step.rest))
            "FACT" -> acc.copy(expectedServerState = acc.expectedServerState.plus(toFacts(step.rest, acc.fixtures)).toMutableMap())
            "PATTERN", "JSON" -> acc.copy(patterns = storePattern(acc.patterns, step.rest, step.rowsList))
            "FIXTURE" -> acc.copy(fixtures = storeFixture(acc.fixtures, step.rest))
            else -> throw ContractParseException("Couldn't recognise the meaning of this command: $step.text")
        }
    }

    return parsedScenarioInfo.copy(examples = scenarioInfo.examples.plus(examplesFrom(examplesList).toMutableList()).toMutableList())
}

fun addToHeaderPattern(rest: String, headersPattern: HttpHeadersPattern): HttpHeadersPattern {
    val parts = breakIntoParts(rest, 2)

    return when (parts.size) {
        2 -> headersPattern.copy(headers = headersPattern.headers.plus(parts[0] to parts[1]).toMutableMap())
        else -> headersPattern
    }
}

private fun breakIntoParts(piece: String, parts: Int) = piece.split("\\s+".toRegex(), parts)

private val HTTP_METHODS = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
internal fun parseGherkinString(gherkinData: String): GherkinDocument {
    val idGenerator: IdGenerator = Incrementing()
    val parser = Parser(GherkinDocumentBuilder(idGenerator))
    return parser.parse(gherkinData).build()
}

internal fun lex(gherkinDocument: GherkinDocument): List<Scenario> {
    val featureChildren = gherkinDocument.feature.childrenList

    val scenarioInfo = featureChildren.find { it.valueCase.name == "BACKGROUND" }?.let {feature ->
        val info = ScenarioInfo(scenarioName = feature.scenario.name).let {
            val backgroundTable = fromPSV(feature.background.description)
            when {
                !backgroundTable.isEmpty -> it.copy(examples = it.examples.plus(backgroundTable).toMutableList())
                else -> it
            }
        }

        lexScenario(feature.background.stepsList, listOf(), info)
    } ?: ScenarioInfo()

    return featureChildren.filter { it.valueCase.name != "BACKGROUND" }.map {
        val info = scenarioInfo?.copy(scenarioName = it.scenario.name)
        val lexedInfo = lexScenario(it.scenario.stepsList, it.scenario.examplesList, info)
        Scenario(lexedInfo.scenarioName, lexedInfo.httpRequestPattern, lexedInfo.httpResponsePattern, HashMap(lexedInfo.expectedServerState), lexedInfo.examples, HashMap(lexedInfo.patterns), HashMap(lexedInfo.fixtures))
    }
}