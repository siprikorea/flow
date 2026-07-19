package dataflow.cli

import dataflow.engine.FlowEngine
import dataflow.model.FlowFile
import dataflow.model.asComponent
import dataflow.platform.Platform
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true }

// 이름(프로젝트 폴더) 또는 파일 경로로 플로우 로드
private fun loadFlow(ref: String): FlowFile? {
    val name = if (ref.endsWith(".json")) ref else "$ref.json"
    val raw = File(ref).takeIf { it.isFile }?.readText()
        ?: File(name).takeIf { it.isFile }?.readText()
        ?: Platform.readFlow(name)
        ?: return null
    return runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull()
}

private fun usage(): Nothing {
    System.err.println(
        """
        DataFlow CLI — 컴포넌트를 실행해 입력→출력을 계산합니다.

        사용법:
          cli <component> <value>                 단일 입력(포트가 하나일 때)
          cli <component> --in <port>=<value> ... 포트별 입력
          cli --list                              컴포넌트 목록

        예:
          cli triple 5
          cli double --in in=10
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()

    if (args[0] == "--list") {
        Platform.listFlows().forEach { f ->
            val comp = loadFlow(f)?.asComponent(f)
            if (comp != null) println("${comp.name}  (${comp.ins.joinToString(",")} → ${comp.outs.joinToString(",")})")
        }
        return
    }

    val ref = args[0]
    val flow = loadFlow(ref) ?: run {
        System.err.println("컴포넌트를 찾을 수 없습니다: $ref")
        exitProcess(1)
    }
    val comp = flow.asComponent(ref) ?: run {
        System.err.println("'$ref' 은 컴포넌트가 아닙니다 (입력/출력 경계 노드 없음).")
        exitProcess(1)
    }

    // 입력 파싱
    val inputs = HashMap<String, String>()
    var idx = 1
    val positional = ArrayList<String>()
    while (idx < args.size) {
        val a = args[idx]
        if (a == "--in") {
            val kv = args.getOrNull(idx + 1) ?: usage()
            val eq = kv.indexOf('=')
            if (eq <= 0) usage()
            inputs[kv.substring(0, eq)] = kv.substring(eq + 1)
            idx += 2
        } else {
            positional.add(a); idx++
        }
    }
    if (inputs.isEmpty() && positional.size == 1 && comp.ins.size == 1) {
        inputs[comp.ins.first()] = positional.first()
    }
    // 미지정 입력은 빈 값
    comp.ins.forEach { inputs.putIfAbsent(it, "") }

    val result = FlowEngine(::loadFlow).run(flow, inputs)
    comp.outs.forEach { out ->
        println("$out = ${result[out] ?: ""}")
    }
}
