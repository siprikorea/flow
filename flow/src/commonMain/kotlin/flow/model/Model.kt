package flow.model

import flow.util.bytesToHex
import flow.util.hexToBytes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// A node port: a name (used for wiring) plus its byte data (the value on the port).
// Serializes as a bare string when it carries no data (compact, and compatible with
// the old List<String> port format), or as {"name","data"} with data as hex.
@Serializable(with = PortSerializer::class)
class Port(val name: String, val data: ByteArray = ByteArray(0)) {
    fun withData(bytes: ByteArray) = Port(name, bytes)
    fun withName(n: String) = Port(n, data)
    override fun equals(other: Any?): Boolean =
        this === other || (other is Port && name == other.name && data.contentEquals(other.data))
    override fun hashCode(): Int = 31 * name.hashCode() + data.contentHashCode()
    override fun toString(): String = "Port($name, ${data.size}B)"
}

// port-list helpers: ports wire by name, so these bridge name-based lookups
fun List<Port>.portNames(): List<String> = map { it.name }
fun List<Port>.indexOfPort(name: String): Int = indexOfFirst { it.name == name }

object PortSerializer : KSerializer<Port> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("flow.model.Port", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Port) {
        val json = encoder as? JsonEncoder ?: error("Port supports only JSON serialization")
        val el = if (value.data.isEmpty()) JsonPrimitive(value.name)
        else buildJsonObject { put("name", value.name); put("data", bytesToHex(value.data)) }
        json.encodeJsonElement(el)
    }

    override fun deserialize(decoder: Decoder): Port {
        val json = decoder as? JsonDecoder ?: error("Port supports only JSON serialization")
        return when (val el = json.decodeJsonElement()) {
            is JsonPrimitive -> Port(el.content) // old bare-string form
            is JsonObject -> Port(
                el["name"]?.jsonPrimitive?.content ?: "",
                hexToBytes(el["data"]?.jsonPrimitive?.content ?: ""),
            )
            else -> Port("")
        }
    }
}

@Serializable
data class PortRef(val node: String, val port: String)

@Serializable
data class Edge(
    val id: String,
    val from: PortRef,
    val to: PortRef,
    val active: Boolean = false,
)

@Serializable
data class Node(
    val id: String,
    val type: String,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val inputs: List<Port>,
    val outputs: List<Port>,
    val params: Map<String, String> = emptyMap(),
    val status: String = "idle", // idle | running | done | error
)

// JSON export / history snapshot format
@Serializable
data class FlowFile(
    val version: Int = 1,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val seq: Int = 1,
)

// What Settings edits, kept apart from the session: these are the user's preferences and outlive
// any particular set of open tabs or window bounds. Stored in settings.json.
@Serializable
data class Settings(
    val lang: String = "ko",
    // system | dark | light; "system" follows the OS setting (see flow.ui.theme.Theme)
    val theme: String = "system",
    // action id -> shortcut id ("meta+n"); missing actions use the default binding
    val keymap: Map<String, String> = emptyMap(),
    val animSeconds: Float = 0.25f,
    // where the Extensions screen looks for installable extensions
    val registryUrl: String = DEFAULT_REGISTRY_URL,
    // outputs and inputs the user has switched off. Kept as the exception rather than the list of
    // enabled ones, so a newly installed one is usable without having to be turned on first.
    val disabledOutputs: List<String> = emptyList(),
    val disabledInputs: List<String> = emptyList(),
)

// The manifest the Extensions screen reads by default. Served straight off the repository, so
// publishing an extension is a commit rather than a deployment.
const val DEFAULT_REGISTRY_URL =
    "https://raw.githubusercontent.com/siprikorea/flow-extensions/main/extensions.json"

// Session format: open tabs + the UI state that goes with them (IntelliJ-style workspace restore).
// The `lang`/`theme`/`keymap`/`animSeconds` fields are the pre-settings.json layout, read once so an
// existing session can be split, and never written again.
@Serializable
data class Session(
    val openFiles: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val showLeft: Boolean = true,
    val leftTab: String = "project",
    // project tree folders left open ("" = the root row itself)
    val expandedDirs: List<String> = listOf(""),
    // palette sections left open
    val expandedSections: List<String> = emptyList(),
    val showProps: Boolean = true,
    val showMinimap: Boolean = true,
    val leftWidth: Float = 240f,
    val propsWidth: Float = 268f,
    // main window bounds (dp); null x/y/width/height = no saved bounds yet, use the default layout
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null,
    val windowMaximized: Boolean = false,
    // ── legacy, migrated into settings.json on first run ──
    val lang: String? = null,
    val theme: String? = null,
    val keymap: Map<String, String> = emptyMap(),
    val animSeconds: Float? = null,
)
