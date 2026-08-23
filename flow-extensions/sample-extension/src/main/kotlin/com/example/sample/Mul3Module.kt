package com.example.sample

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension

/** Module extension: multiplies the numeric input by 3. */
class Mul3Module : ProcessorExtension {
    override val id = "com.example.mul3"
    override val displayName = "×3"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = emptyList<ExtensionOption>()

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val x = inputs["in"]?.decodeToString()?.toDoubleOrNull() ?: 0.0
        val r = x * 3.0
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        return mapOf("out" to s.encodeToByteArray())
    }
}
