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

    @Test
    fun rescueValidationRejectsMalformedExternalInput() {
        assertNull(RescueProtocol.validate("12345", "12345678", null))
        assertNull(RescueProtocol.validate("123456", "short", null))
        assertNull(RescueProtocol.validate("123456", "bad/request", null))
        assertNull(RescueProtocol.validate("123456", "x".repeat(81), null))
    }

    @Test
    fun rescueValidationNormalizesAndAcceptsBoundedRequestId() {
        val request = RescueProtocol.validate(
            "123 456",
            "request-12345678",
            "+10000000000"
        )

        assertEquals("123456", request?.code)
        assertEquals("request-12345678", request?.requestId)
    }
}
