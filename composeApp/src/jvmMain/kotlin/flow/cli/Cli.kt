package flow.cli

import flow.engine.FlowEngine
import flow.model.FlowFile
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true }

// 이름(프로젝트/설치 컴포넌트) 또는 파일 경로로 플로우 로드
private fun loadFlow(ref: String): FlowFile? {
    val name = if (ref.endsWith(".json")) ref else "$ref.json"
    val raw = File(ref).takeIf { it.isFile }?.readText()
        ?: File(name).takeIf { it.isFile }?.readText()
        ?: Platform.readFlow(name)
        ?: Platform.readInstalledComponent(name)
        ?: return null
    return runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull()
}

private fun engine(): FlowEngine {
    val moduleIds = Platform.installedModuleInfos().map { it.id }.toSet()
    return FlowEngine(
        loadFlow = ::loadFlow,
        moduleIds = moduleIds,
        moduleProcess = { id, inputs -> Platform.moduleProcess(id, inputs) },
    )
}

private fun usage(): Nothing {
    System.err.println(
        """
        Flow CLI — 컴포넌트를 실행해 입력→출력을 계산합니다.

        사용법:
          cli <component> <value>                 단일 입력(포트가 하나일 때)
          cli <component> --in <port>=<value> ... 포트별 입력
          cli --list                              컴포넌트 목록(프로젝트+설치)
          cli --install <plugin.jar> [--force]    플러그인 JAR 설치
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()

    when (args[0]) {
        "--install" -> {
            val jar = args.getOrNull(1) ?: usage()
            val force = args.contains("--force")
            val r = Platform.installJar(jar, overwrite = force)
            if (r.conflicts.isNotEmpty()) {
                System.err.println("이미 설치됨: ${r.conflicts.joinToString(", ")} — 덮어쓰려면 --force")
                exitProcess(1)
            }
            println("설치됨: ${r.installed.joinToString(", ").ifBlank { "(플러그인 없음)" }}")
            return
        }
        "--list" -> {
            (Platform.listFlows().mapNotNull { loadFlow(it)?.asComponent(it) } +
                Platform.listInstalledComponents().mapNotNull { loadFlow(it)?.asComponent(it) })
                .distinctBy { it.name }
                .forEach { println("${it.name}  (${it.ins.joinToString(",")} → ${it.outs.joinToString(",")})") }
            if (Platform.installedModuleInfos().isNotEmpty()) {
                println("-- 설치된 모듈 --")
                Platform.installedModuleInfos().forEach { println("${it.id}  (${it.inputs.joinToString(",")} → ${it.outputs.joinToString(",")})") }
            }
            return
        }
    }

    val ref = args[0]
    // 설치된 컴포넌트는 자신의 폴더 샌드박스로 실행, 프로젝트/파일 플로우는 전역 모듈로 실행
    val installedName = if (ref.endsWith(".json")) ref else "$ref.json"
    val isInstalled = File(ref).takeIf { it.isFile } == null &&
        Platform.readFlow(installedName) == null &&
        Platform.readInstalledComponent(installedName) != null

    val flow = loadFlow(ref) ?: run {
        System.err.println("컴포넌트를 찾을 수 없습니다: $ref")
        exitProcess(1)
    }
    val comp = flow.asComponent(ref) ?: run {
        System.err.println("'$ref' 은 컴포넌트가 아닙니다 (입력/출력 경계 노드 없음).")
        exitProcess(1)
    }

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
    comp.ins.forEach { inputs.putIfAbsent(it, "") }

    val result = if (isInstalled) Platform.runComponent(ref.removeSuffix(".json"), inputs) // 샌드박스
    else engine().run(flow, inputs) // 전역 모듈
    comp.outs.forEach { out -> println("$out = ${result[out] ?: ""}") }
}
