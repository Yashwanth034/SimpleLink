package com.simplelink.app.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueProtocolTest {
    @Test
    fun smsMessageRoundTripsAndFitsOneSmsPayload() {
        val requestId = "12345678-1234-1234-1234-123456789012"
        val message = RescueProtocol.createSmsMessage("123456", requestId, "FA+9qCX9VSu")
        val parsed = RescueProtocol.parse(message, "+10000000000")

        assertTrue(message.toByteArray().size <= 140)
        assertEquals("123456", parsed?.code)
        assertEquals(requestId, parsed?.requestId)
    }

    @Test
    fun malformedMessageIsRejected() {
        assertNull(RescueProtocol.parse("not-a-simplelink-message", null))
    }
}
