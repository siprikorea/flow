package com.example.sample

import flow.plugin.ComponentPlugin
import flow.plugin.PluginConn
import flow.plugin.PluginNode

/**
 * Component plugin: a component that triples its input using a single mul3 module.
 * Defines only connections, without coordinates (auto-laid-out on install).
 */
class TripleComponent : ComponentPlugin {
    override val id = "com.example.triple"
    override val displayName = "triple"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override fun nodes(): List<PluginNode> = listOf(
        PluginNode(id = "m", type = "com.example.mul3", inputs = listOf("in"), outputs = listOf("out")),
    )

    override fun connections(): List<PluginConn> = emptyList()

    override fun inputBindings(): Map<String, String> = mapOf("in" to "m.in")
    override fun outputBindings(): Map<String, String> = mapOf("out" to "m.out")
}
