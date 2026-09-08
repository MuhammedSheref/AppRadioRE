package com.ameer.appradiore.logging

import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.logging.ProtocolType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRepositoryTest {

    @Test
    fun testAddLogEntry() = runTest {
        val repo = LogRepositoryImpl(maxCapacity = 10)
        repo.log(
            direction = LogDirection.INCOMING,
            protocol = ProtocolType.SAC,
            summary = "AuthResponse",
            rawHex = "9F 02 00 9F 03"
        )

        val logs = repo.logs.first()
        assertEquals(1, logs.size)
        assertEquals("AuthResponse", logs[0].summary)
        assertEquals(ProtocolType.SAC, logs[0].protocol)
        assertEquals(LogDirection.INCOMING, logs[0].direction)
    }

    @Test
    fun testCapacityEnforcement() = runTest {
        val maxCap = 5
        val repo = LogRepositoryImpl(maxCapacity = maxCap)

        for (i in 1..10) {
            repo.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.WEBLINK,
                summary = "Packet $i"
            )
        }

        val logs = repo.logs.first()
        assertEquals(maxCap, logs.size)
        assertEquals("Packet 6", logs[0].summary)
        assertEquals("Packet 10", logs.last().summary)
    }

    @Test
    fun testClearLogs() = runTest {
        val repo = LogRepositoryImpl()
        repo.log(
            direction = LogDirection.INTERNAL,
            protocol = ProtocolType.SYSTEM,
            summary = "Initialized"
        )
        assertEquals(1, repo.logs.first().size)

        repo.clearLogs()
        assertTrue(repo.logs.first().isEmpty())
    }
}
