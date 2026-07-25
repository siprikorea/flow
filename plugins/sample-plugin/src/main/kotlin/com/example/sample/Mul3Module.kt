package com.example.sample

import flow.plugin.ModulePlugin

/** Module plugin: multiplies the numeric input by 3. */
class Mul3Module : ModulePlugin {
    override val id = "com.example.mul3"
    override val displayName = "×3"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override fun process(inputs: Map<String, String?>): Map<String, String?> {
        val x = inputs["in"]?.toDoubleOrNull() ?: 0.0
        val r = x * 3.0
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        return mapOf("out" to s)
    }
}
