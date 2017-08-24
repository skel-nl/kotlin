
annotation class IntRange(val from: Long, val to: Long)

annotation class RequiresPermission(val anyOf: IntArray)

@RequiresPermission(anyOf = arrayOf(1, 2, 3))
@IntRange(from = 10, to = 0)
fun foo(): Int = 5