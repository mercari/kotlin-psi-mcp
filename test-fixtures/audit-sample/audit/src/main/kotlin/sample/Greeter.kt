package sample

/**
 * A simple greeter used as an audit fixture.
 */
class Greeter(val name: String) {

    fun greet(): String {
        return buildMessage(name)
    }

    private fun buildMessage(who: String): String {
        return "Hello, $who!"
    }
}

fun topLevelGreeting(target: String): String {
    val greeter = Greeter(target)
    return greeter.greet()
}
