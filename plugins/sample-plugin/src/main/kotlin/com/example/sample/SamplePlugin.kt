package com.example.sample

import dataflow.plugin.Plugin
import dataflow.plugin.PluginComponent

/**
 * 예시 플러그인: "triple" 컴포넌트(입력 → x3 매핑 → 출력)를 제공한다.
 * 빌드된 JAR 을 ~/.dataflow-editor/plugins/ 에 복사하면 앱 팔레트의
 * Components 섹션에 나타난다.
 */
class SamplePlugin : Plugin {
    override val id: String = "com.example.sample"

    override fun components(): List<PluginComponent> = listOf(
        PluginComponent(
            fileName = "triple.json",
            flowJson = TRIPLE_FLOW,
        ),
    )
}

// cin("in") → map(x3) → cout("out") 형태의 컴포넌트 플로우
private val TRIPLE_FLOW = """
{
  "version": 1,
  "seq": 4,
  "nodes": [
    {"id":"cin_1","type":"cin","label":"in","x":60.0,"y":120.0,"w":160.0,"h":80.0,"inputs":[],"outputs":["out"],"params":{},"status":"idle"},
    {"id":"map_2","type":"map","label":"x3","x":300.0,"y":120.0,"w":180.0,"h":100.0,"inputs":["in"],"outputs":["out"],"params":{"expr":"x * 3"},"status":"idle"},
    {"id":"cout_3","type":"cout","label":"out","x":560.0,"y":120.0,"w":160.0,"h":80.0,"inputs":["in"],"outputs":[],"params":{},"status":"idle"}
  ],
  "edges": [
    {"id":"e_1","from":{"node":"cin_1","port":"out"},"to":{"node":"map_2","port":"in"},"active":false},
    {"id":"e_2","from":{"node":"map_2","port":"out"},"to":{"node":"cout_3","port":"in"},"active":false}
  ]
}
""".trimIndent()
