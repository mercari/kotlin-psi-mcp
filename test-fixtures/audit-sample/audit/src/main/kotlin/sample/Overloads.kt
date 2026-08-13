package sample

/**
 * Overload set for auditing find-declaration overload resolution.
 *
 * Each overload sits on its own line so the resolved declaration line is
 * unambiguous. A call site must resolve to the overload whose signature
 * matches its arguments, NOT merely the first "format" found by name.
 */
object Formatter {
    fun format(value: Int): String = "int:$value"

    fun format(value: String): String = "str:$value"

    fun format(value: Int, width: Int): String = "int:$value w$width"
}

fun useOverloads(): String {
    val a = Formatter.format(42)
    val b = Formatter.format("x")
    val c = Formatter.format(42, 8)
    return a + b + c
}
