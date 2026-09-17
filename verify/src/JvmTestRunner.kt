// Minimal JVM test runner for the local `verify` harness (no Gradle, no Android SDK).
// Discovers every method annotated with @org.junit.Test in the given test classes and runs them,
// mirroring what `./gradlew testDebugUnitTest` does in CI for the pure Kotlin parts of the app.
// Only compiled/used by the local harness in verify/README.md — never part of the Gradle build.

package com.souxch.watermarkremover.verify

import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible
import kotlin.system.exitProcess

object JvmTestRunner {
    fun run(classes: List<KClass<*>>): Int {
        var run = 0
        var failed = 0
        val failures = mutableListOf<String>()
        for (cls in classes) {
            val instance = cls.companionObjectInstance
                ?: cls.objectInstance
                ?: runCatching { cls.constructors.first().call() }.getOrNull()
                ?: error("cannot instantiate ${cls.simpleName}: needs a no-arg constructor")
            val methods = cls.functions.filter { it.annotations.any { a -> a is Test } }
            for (m in methods) {
                val label = "${cls.simpleName}.${m.name}"
                run++
                try {
                    m.isAccessible = true
                    m.call(instance)
                    println("ok   $label")
                } catch (e: Throwable) {
                    failed++
                    val cause = generateSequence(e) { it.cause }.last()
                    failures.add("FAIL $label\n     ${cause.javaClass.simpleName}: ${cause.message}")
                    println("FAIL $label -> ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
        }
        println("\n$run test(s), $failed failure(s)")
        if (failed > 0) {
            println("\n-- failures --")
            failures.forEach { println(it) }
        }
        return failed
    }
}
