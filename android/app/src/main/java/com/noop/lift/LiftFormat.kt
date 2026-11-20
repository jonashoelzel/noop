package com.noop.lift

import com.noop.ui.UnitFormatter
import com.noop.ui.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Number and unit formatting for the Lift Log. Kotlin twin of `Strand/Data/LiftFormat.swift`.
 *
 * WEIGHT IS ALWAYS STORED IN KILOGRAMS. Only display and typed input convert, using the unit system the
 * user already picked for the whole app; the Lift Log adds no weight-unit setting of its own. Conversion
 * goes through ONE constant both ways ([UnitFormatter.POUNDS_PER_KILOGRAM]), so a typed value round-trips:
 * enter 225 lb, store 102.058… kg, read back 225 lb.
 */
object LiftFormat {

    /** A typed weight in the user's display unit, as the kilograms that get stored. */
    fun kilograms(fromDisplay: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) fromDisplay / UnitFormatter.POUNDS_PER_KILOGRAM else fromDisplay

    /** Stored kilograms in the user's display unit, as a number for an editable field. */
    fun display(fromKilograms: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) fromKilograms * UnitFormatter.POUNDS_PER_KILOGRAM else fromKilograms

    /** A stored weight for display with its unit: "60 kg", "132.5 lb"; "—" when absent. */
    fun weight(kg: Double?, system: UnitSystem): String =
        if (kg == null) "—" else "${trim(display(kg, system))} ${weightUnit(system)}"

    /** The bare unit label for a field suffix. */
    fun weightUnit(system: UnitSystem): String = UnitFormatter.massUnit(system)

    /**
     * Up to TWO decimals with no trailing noise: 8.0 → "8", 7.5 → "7.5", 12.25 → "12.25". Two, because
     * 12.25 kg is a real plate weight and an entry field that reads its text back through this must not
     * round what was typed. Always "." whatever the locale, as on Apple.
     */
    fun trim(value: Double): String {
        val rounded = schoolRound(value * 100) / 100
        if (rounded == schoolRound(rounded) && abs(rounded) < 1e9) return schoolRound(rounded).toLong().toString()
        var text = String.format(Locale.ROOT, "%.2f", rounded)
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
        return text
    }

    /** Swift's `rounded()`: half away from zero. Kotlin's `round` is half-even, which would differ at .5. */
    private fun schoolRound(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

    /** Parse a typed number, accepting "7.5" and the comma separator "7,5". Null for anything else. */
    fun number(text: String): Double? {
        val cleaned = text.trim().replace(",", ".")
        if (cleaned.isEmpty()) return null
        // Swift's Double(String) takes plain decimal notation only; refuse what it would refuse.
        if (!Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").matches(cleaned)) return null
        return cleaned.toDoubleOrNull()
    }

    /** A rest as "2:00" / "45s": minutes and seconds, how rest is spoken about. */
    fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
