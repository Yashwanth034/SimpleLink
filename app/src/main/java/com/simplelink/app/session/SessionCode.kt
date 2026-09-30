package com.simplelink.app.session

import java.security.SecureRandom

object SessionCode {
    private val random = SecureRandom()

    fun generate(): String = (random.nextInt(900_000) + 100_000).toString()

    fun normalize(value: String): String = value.filter(Char::isDigit).take(6)

    fun format(value: String): String {
        val digits = normalize(value)
        return if (digits.length <= 3) digits else "${digits.take(3)} ${digits.drop(3)}"
    }

    fun isValid(value: String): Boolean = normalize(value).length == 6
}
