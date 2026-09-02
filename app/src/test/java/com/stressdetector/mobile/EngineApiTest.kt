package com.stressdetector.mobile

import java.lang.reflect.Modifier
import org.junit.Test

class EngineApiTest {
    @Test
    fun printEngineMethods() {
        try {
            val sb = java.lang.StringBuilder()

            fun printClass(className: String) {
                try {
                    val clazz = Class.forName(className)
                    sb.append("--- $className METHODS ---\n")
                    clazz.declaredMethods.forEach { method ->
                        val modifiers = Modifier.toString(method.modifiers)
                        val returnType = method.returnType.simpleName
                        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                        sb.append("$modifiers $returnType ${method.name}($params)\n")
                    }
                } catch (e: Exception) {
                    sb.append("$className not found\n")
                }
            }

            val engineConfig = com.google.ai.edge.litertlm.EngineConfig(modelPath = "dummy")
            val engine = com.google.ai.edge.litertlm.Engine(engineConfig)
            // Just inspect the methods of the Conversation object and Message object properly
            printClass("com.google.ai.edge.litertlm.InputData")

            // Let's inspect constructors of InputData
            try {
                val clazz = Class.forName("com.google.ai.edge.litertlm.InputData")
                clazz.constructors.forEach { sb.append("Constructor: $it\n") }
                clazz.declaredMethods.forEach { sb.append("Method: $it\n") }
            } catch (e: Exception) {}
            printClass("com.google.ai.edge.litertlm.Contents")
            printClass("com.google.ai.edge.litertlm.InputData")

            java.io.File("build/engine_api.txt").writeText(sb.toString())
        } catch (e: Exception) {
            java.io.File("build/engine_api.txt").writeText(e.stackTraceToString())
        }
    }
}
