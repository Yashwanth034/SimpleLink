package com.simplelink.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodeTest {
    @Test
    fun generatedCodesAreSixDigits() {
        repeat(1_000) {
            val code = SessionCode.generate()
            assertTrue(SessionCode.isValid(code))
            assertEquals(code, SessionCode.normalize(SessionCode.format(code)))
        }
    }
}
