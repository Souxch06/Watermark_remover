import java.lang.reflect.Method

/** Reflective runner for the org.junit shim: runs every @Test method, reports PASS/FAIL. */
fun main(args: Array<String>) {
    val classNames: List<String> = if (args.isEmpty()) {
        listOf("com.souxch.watermarkremover.processing.WatermarkAnalyzerTest")
    } else {
        args.toList()
    }
    val testAnnotation = org.junit.Test::class.java
    var passed = 0
    var failed = 0
    for (cn in classNames) {
        val cls = Class.forName(cn)
        val instance = cls.getDeclaredConstructor().newInstance()
        val methods: List<Method> = cls.declaredMethods
            .filter { it.isAnnotationPresent(testAnnotation) }
            .sortedBy { it.name }
        for (m in methods) {
            try {
                m.isAccessible = true
                m.invoke(instance)
                passed++
                println("PASS  ${m.name}")
            } catch (e: Exception) {
                failed++
                val cause = e.cause ?: e
                println("FAIL  ${m.name}")
                cause.printStackTrace(System.out)
            }
        }
    }
    println("--- $passed passed, $failed failed ---")
    if (failed > 0) kotlin.system.exitProcess(1)
}
