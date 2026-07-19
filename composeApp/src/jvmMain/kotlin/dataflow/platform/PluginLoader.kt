package dataflow.platform

import dataflow.plugin.Plugin
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

// ~/.dataflow-editor/plugins/*.jar 를 로드해 플러그인이 제공하는 컴포넌트를
// 프로젝트 폴더에 반영한다. 파일명은 순수 ".json" 파일명만 허용(경로 탈출 차단).
internal object PluginLoader {
    fun loadInto(pluginsDir: File, flowsDir: File) {
        val jars = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: return
        if (jars.isEmpty()) return
        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        // 부모 = 앱 클래스로더. 그래야 JAR 의 구현이 앱의 Plugin 인터페이스로 해석된다.
        val loader = URLClassLoader(urls, Plugin::class.java.classLoader)
        runCatching {
            ServiceLoader.load(Plugin::class.java, loader).forEach { plugin ->
                runCatching {
                    plugin.components().forEach { comp ->
                        val name = comp.fileName
                        val safe = name.endsWith(".json") &&
                            !name.contains('/') && !name.contains('\\') &&
                            !name.contains(' ') && name != "." && name != ".."
                        if (!safe) return@forEach
                        val target = File(flowsDir, name)
                        // write-if-absent: 사용자 편집을 덮어쓰지 않음
                        if (!target.exists()) target.writeText(comp.flowJson)
                    }
                }
            }
        }
    }
}
