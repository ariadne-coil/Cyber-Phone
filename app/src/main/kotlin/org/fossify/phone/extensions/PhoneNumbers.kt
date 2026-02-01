package org.fossify.phone.extensions

import org.fossify.commons.extensions.normalizePhoneNumber

fun arePhoneNumbersEquivalent(
    numberA: String,
    numberB: String,
    comparableLength: Int = 9,
): Boolean {
    val normalizedA = numberA.normalizePhoneNumber()
    val normalizedB = numberB.normalizePhoneNumber()
    if (normalizedA.isEmpty() || normalizedB.isEmpty()) {
        return false
    }
    if (normalizedA == normalizedB) {
        return true
    }
    val tailLength = minOf(comparableLength, normalizedA.length, normalizedB.length)
    return normalizedA.takeLast(tailLength) == normalizedB.takeLast(tailLength)
}
