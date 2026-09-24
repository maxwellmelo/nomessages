package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.ui.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TransportSupervisorTest {
    /** Ends the endless supervision loop from inside a fake, without pretending to be a lock. */
    private class StopLoop : RuntimeException()

    private val emitted = mutableListOf<NetworkStatus>()
    private val slept = mutableListOf<Long>()

    private fun newSupervisor(sleep: suspend (Long) -> Unit = { slept += it }) =
        TransportSupervisor(onStatus = { emitted += it }, sleep = sleep)

    @Test fun failedActivationRetriesAndThenComesOnline() {
        val supervisor = newSupervisor()
        var attempts = 0
        try {
            runBlocking {
                supervisor.supervise(
                    attempt = {
                        attempts++
                        if (attempts == 1) TransportAttempt.FAILED else TransportAttempt.ONLINE
                    },
                    hold = { throw StopLoop() },
                )
            }
            fail("supervision must not return on its own")
        } catch (_: StopLoop) {
        }
        assertEquals(2, attempts)
        assertEquals(listOf(NetworkStatus.STARTING, NetworkStatus.RETRYING, NetworkStatus.ONLINE), emitted)
        assertEquals(listOf(5_000L), slept)
        assertEquals(NetworkStatus.ONLINE, supervisor.status)
        assertEquals(0, supervisor.failureCount)
    }

    @Test fun publishingIsReportedBeforeOnlineWithinTheSameAttempt() {
        val supervisor = newSupervisor()
        try {
            runBlocking {
                supervisor.supervise(
                    attempt = { supervisor.publishing(); TransportAttempt.ONLINE },
                    hold = { throw StopLoop() },
                )
            }
            fail("supervision must not return on its own")
        } catch (_: StopLoop) {
        }
        assertEquals(listOf(NetworkStatus.STARTING, NetworkStatus.PUBLISHING, NetworkStatus.ONLINE), emitted)
        assertEquals(emptyList<Long>(), slept)
    }

    /**
     * Reachability is reversible, so an online transport must be able to say so and to take it
     * back without a reconnection: no attempt is repeated and no backoff is slept here.
     */
    @Test fun reachabilityIsReportedBothWaysWhileTheSameTransportIsHeld() {
        val supervisor = newSupervisor()
        var attempts = 0
        try {
            runBlocking {
                supervisor.supervise(
                    attempt = { attempts++; TransportAttempt.ONLINE },
                    hold = {
                        // Already online: a redundant report must not repaint the banner.
                        supervisor.reachable()
                        supervisor.publishing()
                        supervisor.reachable()
                        throw StopLoop()
                    },
                )
            }
            fail("supervision must not return on its own")
        } catch (_: StopLoop) {
        }
        assertEquals(1, attempts)
        assertEquals(
            listOf(
                NetworkStatus.STARTING,
                NetworkStatus.ONLINE,
                NetworkStatus.PUBLISHING,
                NetworkStatus.ONLINE,
            ),
            emitted,
        )
        assertEquals(emptyList<Long>(), slept)
        assertEquals(0, supervisor.failureCount)
    }

    @Test fun lockCancelsTheBackoffAndLeavesNoStaleBanner() {
        val supervisor = newSupervisor(sleep = { millis ->
            slept += millis
            throw CancellationException("lock")
        })
        var attempts = 0
        try {
            runBlocking {
                supervisor.supervise(
                    attempt = { attempts++; TransportAttempt.FAILED },
                    hold = { fail("a failed attempt never goes online") },
                )
            }
            fail("cancellation must propagate out of supervision")
        } catch (_: CancellationException) {
        }
        assertEquals(1, attempts)
        assertEquals(listOf(NetworkStatus.STARTING, NetworkStatus.RETRYING, NetworkStatus.OFF), emitted)
        assertEquals(listOf(5_000L), slept)
        assertEquals(NetworkStatus.OFF, supervisor.status)
    }

    @Test fun backoffGrowsToAFiveMinuteCeiling() {
        val supervisor = newSupervisor()
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 60_000L, 120_000L, 300_000L),
            (1..6).map { supervisor.delayFor(it) },
        )
        assertEquals(TransportSupervisor.MAX_BACKOFF_MILLIS, supervisor.delayFor(7))
        assertEquals(TransportSupervisor.MAX_BACKOFF_MILLIS, supervisor.delayFor(64))
    }

    @Test fun transportLostWhileOnlineReconnectsWithoutInheritingOldFailures() {
        val supervisor = newSupervisor()
        val outcomes = ArrayDeque(
            listOf(
                TransportAttempt.FAILED,
                TransportAttempt.FAILED,
                TransportAttempt.ONLINE,
                TransportAttempt.FAILED,
                TransportAttempt.ONLINE,
            ),
        )
        var holds = 0
        try {
            runBlocking {
                supervisor.supervise(
                    attempt = { outcomes.removeFirst() },
                    hold = { holds++; if (holds == 2) throw StopLoop() },
                )
            }
            fail("supervision must not return on its own")
        } catch (_: StopLoop) {
        }
        assertEquals(0, outcomes.size)
        // The outage after ONLINE restarts the schedule at 5 s instead of inheriting the 20 s step.
        assertEquals(listOf(5_000L, 10_000L, 5_000L), slept)
        assertEquals(
            listOf(
                NetworkStatus.STARTING,
                NetworkStatus.RETRYING,
                NetworkStatus.ONLINE,
                NetworkStatus.RETRYING,
                NetworkStatus.ONLINE,
            ),
            emitted,
        )
    }
}
