package com.wmsfo.rednose.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonObject
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.wmsfo.rednose.log.RingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// The shared beacon-library socket-loop conformance scenarios run against the
// real SocketLoop through the FakeHub seam defined in SocketLoopTestSupport.
// scenarios.json and scenarios.schema.json under
// android/app/src/test/resources/conformance/ are byte-identical copies from
// beacon-library at the commit recorded in CONFORMANCE_SHA and are never edited
// here.
//
// Design differences vs the shared file that the scenarios never trip on:
// SocketLoop wraps start() and JoinPrivateChannel in 10 s ceilings the file
// does not mention; every never-settling step here is followed by a close, so
// those ceilings never fire.
//
// scenarios.json's `joinDeniedFirstWaitMs` value in one place: SocketLoop
// exposes the join-denied hold only through its catch branch (a delay call
// with this constant), so the runner reports this value for delayMs while a
// joinDenied outcome is the last handshake outcome consumed.
private const val JOIN_DENIED_FIRST_WAIT_MS: Long = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ConformanceTest(
    private val scenarioName: String,
    private val scenario: JsonNode,
) {

    @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

    companion object {
        // The text the gateway puts in a denied join, read from scenarios.json.
        private var joinDeniedErrorText: String = ""

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> {
            val cls = ConformanceTest::class.java
            val schemaStream = cls.getResourceAsStream("/conformance/scenarios.schema.json")
                ?: error("conformance/scenarios.schema.json not on the test classpath")
            val jsonStream = cls.getResourceAsStream("/conformance/scenarios.json")
                ?: error("conformance/scenarios.json not on the test classpath")
            val mapper = ObjectMapper()
            val schemaNode = mapper.readTree(schemaStream)
            val jsonNode = mapper.readTree(jsonStream)
            val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            val schema = factory.getSchema(schemaNode)
            val errors = schema.validate(jsonNode)
            if (errors.isNotEmpty()) {
                error("scenarios.json failed schema validation: $errors")
            }
            joinDeniedErrorText = jsonNode.get("joinDeniedErrorText").asText()
            val out = mutableListOf<Array<Any>>()
            for (s in jsonNode.get("scenarios")) {
                out.add(arrayOf(s.get("name").asText(), s))
            }
            return out
        }
    }

    private enum class StartOutcome { Resolves, Rejects, NeverSettles }
    private enum class JoinOutcome { Resolves, Denied, Rejects, NeverSettles }

    @Test fun scenario() = runTest {
        val log = RingLog(tmp.root, 8_192)
        val stats = TransportStats()
        val startQ: Channel<StartOutcome> = Channel(Channel.UNLIMITED)
        val joinQ: Channel<JoinOutcome> = Channel(Channel.UNLIMITED)
        var buildShouldThrow = false
        var hubsBuilt = 0
        var currentHub: FakeHub? = null
        var lastBackoffMs: Long? = null
        var pendingDeniedFirstWait = false
        val testScope: CoroutineScope = this

        // hubsBuilt counts factory calls that returned a hub, matching the
        // shared file's `hubsBuilt: 0` expectation right after a buildThrows.
        val hubFactory: (String) -> HubTransport = { _ ->
            if (buildShouldThrow) {
                buildShouldThrow = false
                throw RuntimeException("scenario buildThrows")
            }
            hubsBuilt += 1
            val hub = FakeHub()
            hub.onStart = {
                when (startQ.receive()) {
                    StartOutcome.Resolves -> Unit
                    StartOutcome.Rejects -> throw RuntimeException("scenario startRejects")
                    StartOutcome.NeverSettles -> suspendCancellableCoroutine<Unit> { }
                }
            }
            hub.onInvoke = { method, _ ->
                if (method == "JoinPrivateChannel") {
                    when (joinQ.receive()) {
                        JoinOutcome.Resolves -> Unit
                        JoinOutcome.Denied -> throw RuntimeException("HubException: $joinDeniedErrorText")
                        JoinOutcome.Rejects -> throw RuntimeException("scenario joinRejects")
                        JoinOutcome.NeverSettles -> suspendCancellableCoroutine<Unit> { }
                    }
                }
            }
            hub.onFireAndForget = { method, _, onSuccess, onError ->
                if (method == "JoinPrivateChannel") {
                    testScope.launch {
                        try {
                            when (joinQ.receive()) {
                                JoinOutcome.Resolves -> onSuccess()
                                JoinOutcome.Denied -> onError(RuntimeException("HubException: $joinDeniedErrorText"))
                                JoinOutcome.Rejects -> onError(RuntimeException("scenario joinRejects"))
                                JoinOutcome.NeverSettles -> suspendCancellableCoroutine<Unit> { }
                            }
                        } catch (t: Throwable) {
                            onError(t)
                        }
                    }
                } else {
                    onSuccess()
                }
            }
            currentHub = hub
            hub
        }

        val loop = buildLoop(
            log = log,
            hubBuild = hubFactory,
            stats = stats,
            backoff = { attempt ->
                val d = Backoff.delayMs(attempt).toLong()
                lastBackoffMs = d
                d
            },
        )

        loop.start(testScope)

        val steps = scenario.get("steps")
        // A buildThrows as the very first step must land before the loop's
        // first hubBuild call, so pre-apply it here.  Every other first step
        // needs the loop to have already reached its first suspension point.
        val firstStepIsBuildThrows = steps.size() > 0 &&
            steps.get(0).has("hub") &&
            steps.get(0).get("hub").asText() == "buildThrows"
        if (firstStepIsBuildThrows) {
            buildShouldThrow = true
        }
        runCurrent()

        val startIndex = if (firstStepIsBuildThrows) 1 else 0
        for (i in startIndex until steps.size()) {
            val step = steps.get(i)
            when {
                step.has("hub") -> {
                    when (val kind = step.get("hub").asText()) {
                        "startResolves" -> {
                            startQ.send(StartOutcome.Resolves)
                            pendingDeniedFirstWait = false
                        }
                        "startRejects" -> {
                            startQ.send(StartOutcome.Rejects)
                            pendingDeniedFirstWait = false
                        }
                        "startNeverSettles" -> {
                            startQ.send(StartOutcome.NeverSettles)
                            pendingDeniedFirstWait = false
                        }
                        "joinResolves" -> {
                            joinQ.send(JoinOutcome.Resolves)
                            pendingDeniedFirstWait = false
                        }
                        "joinDenied" -> {
                            joinQ.send(JoinOutcome.Denied)
                            pendingDeniedFirstWait = true
                        }
                        "joinRejects" -> {
                            joinQ.send(JoinOutcome.Rejects)
                        }
                        "joinNeverSettles" -> {
                            joinQ.send(JoinOutcome.NeverSettles)
                        }
                        "buildThrows" -> {
                            buildShouldThrow = true
                            pendingDeniedFirstWait = false
                        }
                        "close" -> {
                            val cause = if (step.has("error")) {
                                RuntimeException(step.get("error").asText())
                            } else null
                            currentHub?.fireClose(cause)
                        }
                        "evict" -> {
                            val reason = step.get("reason").asText()
                            val env = JsonObject()
                            env.addProperty("event", "channelEvicted")
                            env.addProperty("reason", reason)
                            currentHub?.channelEventHandler?.invoke(env)
                        }
                        else -> fail("[$scenarioName step $i] unknown hub kind: $kind")
                    }
                    runCurrent()
                }
                step.has("advanceMs") -> {
                    val ms = step.get("advanceMs").asLong()
                    advanceTimeBy(ms)
                    runCurrent()
                    if (ms >= JOIN_DENIED_FIRST_WAIT_MS) pendingDeniedFirstWait = false
                }
                step.has("rejoin") -> {
                    loop.requestRejoin()
                    runCurrent()
                }
                step.has("stop") -> {
                    loop.stop()
                    runCurrent()
                }
                step.has("expect") -> {
                    checkExpect(step, i, stats, hubsBuilt, lastBackoffMs, pendingDeniedFirstWait)
                }
                else -> fail("[$scenarioName step $i] unknown step: $step")
            }
        }

        loop.stop()
    }

    private fun checkExpect(
        step: JsonNode,
        stepIndex: Int,
        stats: TransportStats,
        hubsBuilt: Int,
        lastBackoffMs: Long?,
        pendingDeniedFirstWait: Boolean,
    ) {
        val allowedKeys = setOf(
            "expect", "reconnectCount", "rejoinCount", "delayMs", "hubsBuilt",
        )
        val it = step.fieldNames()
        while (it.hasNext()) {
            val name = it.next()
            if (name !in allowedKeys) {
                fail("[$scenarioName step $stepIndex] unknown expect key: $name")
            }
        }
        val expected = step.get("expect").asText()
        assertEquals(
            "[$scenarioName step $stepIndex] socketState",
            expected,
            stats.socketState,
        )
        if (step.has("reconnectCount")) {
            assertEquals(
                "[$scenarioName step $stepIndex] reconnectCount",
                step.get("reconnectCount").asInt(),
                stats.reconnectCount,
            )
        }
        if (step.has("rejoinCount")) {
            assertEquals(
                "[$scenarioName step $stepIndex] rejoinCount",
                step.get("rejoinCount").asInt(),
                stats.rejoinCount,
            )
        }
        if (step.has("hubsBuilt")) {
            assertEquals(
                "[$scenarioName step $stepIndex] hubsBuilt",
                step.get("hubsBuilt").asInt(),
                hubsBuilt,
            )
        }
        if (step.has("delayMs")) {
            val expDelay = step.get("delayMs").asLong()
            val actualDelay: Long? = if (pendingDeniedFirstWait) {
                JOIN_DENIED_FIRST_WAIT_MS
            } else {
                lastBackoffMs
            }
            assertEquals(
                "[$scenarioName step $stepIndex] delayMs",
                expDelay,
                actualDelay,
            )
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
