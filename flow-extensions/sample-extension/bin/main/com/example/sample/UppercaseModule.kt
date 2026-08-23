package com.example.sample

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension

/** Module extension: converts the input text to upper case. */
class UppercaseModule : ModuleExtension {
    override val id = "com.example.upper"
    override val displayName = "UPPER"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = emptyList<ExtensionOption>()

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        mapOf("out" to inputs["in"]?.decodeToString()?.uppercase()?.encodeToByteArray())
}
