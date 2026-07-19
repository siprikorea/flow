package com.example.sample

import flow.plugin.ComponentPlugin
import flow.plugin.PluginConn
import flow.plugin.PluginNode

/**
 * 컴포넌트 플러그인: mul3 모듈 하나로 입력을 3배 하는 컴포넌트.
 * 좌표 정보 없이 연결만 정의한다(설치 시 자동 배치).
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
