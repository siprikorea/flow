package com.example.sample

import flow.plugin.ModulePlugin

/** Module plugin: converts the input text to upper case. */
class UppercaseModule : ModulePlugin {
    override val id = "com.example.upper"
    override val displayName = "UPPER"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override fun process(inputs: Map<String, String?>): Map<String, String?> =
        mapOf("out" to (inputs["in"]?.uppercase()))
}
