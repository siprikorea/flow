package flow.util

// Project paths are relative to the flows root; the root itself is "".

fun pathName(path: String): String = path.substringAfterLast('/')

fun pathParent(path: String): String = path.substringBeforeLast('/', "")

fun pathJoin(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

// [path] is [dir] or lives under it
fun pathUnder(path: String, dir: String): Boolean =
    dir.isEmpty() || path == dir || path.startsWith("$dir/")

// "a/b" -> ["", "a", "a/b"]
fun pathAncestors(dir: String): List<String> {
    val out = mutableListOf("")
    if (dir.isEmpty()) return out
    var acc = ""
    dir.split('/').forEach { seg ->
        acc = pathJoin(acc, seg)
        out += acc
    }
    return out
}

fun flowLabel(path: String): String = pathName(path).removeSuffix(".flow")

// one path segment, no separators or traversal
fun isValidSegment(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
