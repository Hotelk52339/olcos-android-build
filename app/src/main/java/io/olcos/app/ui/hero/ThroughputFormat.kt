package io.olcos.app.ui.hero

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max

/**
 * A smoothed rate for the readout, as the iOS app measures it. Nothing here
 * invents numbers: no sample means no reading, and the picture falls back to
 * its calm drift.
 */
sealed class ThroughputReading {
    /** Exact packet-path counters (VPN mode): toward the device (↓) and from it (↑). */
    data class Exact(val inBytesPerSecond: Double, val outBytesPerSecond: Double) : ThroughputReading() {
        override val totalBytesPerSecond: Double get() = inBytesPerSecond + outBytesPerSecond
    }

    /** A loopback estimate (the in-app proxy): one undirected figure, it cannot be split into ↓ and ↑. */
    data class Estimate(override val totalBytesPerSecond: Double) : ThroughputReading()

    abstract val totalBytesPerSecond: Double

    /**
     * 0…1 part of the traffic flowing toward the device. Exact counters split
     * it; the undirected estimate (and a silent tunnel) report the even 0.5.
     */
    val inboundShare: Double
        get() = when (this) {
            is Exact -> {
                val total = inBytesPerSecond + outBytesPerSecond
                if (!total.isFinite() || total <= 0 || !inBytesPerSecond.isFinite() || inBytesPerSecond < 0) {
                    0.5
                } else {
                    clampUnit(inBytesPerSecond / total)
                }
            }
            is Estimate -> 0.5
        }
}

/**
 * Human figures for the readout, the way speed tests count: bits, in decimal
 * kilo and mega ("0 Kbit/s" … "999 Kbit/s", "4.5 Mbit/s", "120 Mbit/s").
 */
object ThroughputFormat {
    fun bits(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String {
        val value = if (bytesPerSecond.isFinite()) max(0.0, bytesPerSecond) * 8 else 0.0
        if (value < 1_000_000) {
            return number(roundHalfAway(value / 1_000), fractionDigits = 0, locale = locale) + " " + kilobits(locale)
        }
        val mbit = value / 1_000_000
        return number(mbit, fractionDigits = if (mbit < 100) 1 else 0, locale = locale) + " " + megabits(locale)
    }

    /**
     * The locale's decimal separator and no thousands grouping: "1023", "1.2" or
     * "1,2". Grouping ("1,023") would read as a decimal in half the locales.
     * Rounds the exact binary value half to even, like C's printf the iOS app
     * formats with.
     */
    fun number(value: Double, fractionDigits: Int, locale: Locale): String {
        if (!value.isFinite()) return value.toString()
        val plain = BigDecimal(value).setScale(max(0, fractionDigits), RoundingMode.HALF_EVEN).toPlainString()
        if (fractionDigits <= 0) return plain
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') plain else plain.replace('.', separator)
    }

    // The app speaks English and Russian (values, values-ru); the unit words follow it.
    private fun kilobits(locale: Locale): String = if (locale.language == "ru") "Кбит/с" else "Kbit/s"

    private fun megabits(locale: Locale): String = if (locale.language == "ru") "Мбит/с" else "Mbit/s"
}
