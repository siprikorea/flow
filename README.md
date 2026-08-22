# Flow

A visual data-transformation pipeline editor. Drag modules (sources, transforms, sinks) onto a canvas, connect output ports to input ports with bezier curves, and build a data flow. Start/stop a run simulation (packets animate along the curves while node statuses update), and persist flows via auto-save and JSON export/import.

Built with **Compose Multiplatform** (Kotlin). All UI, state, and logic live in `commonMain`; `jvmMain` only provides the entry point and file I/O. It currently runs on the desktop (JVM) target.

## Requirements
- JDK 17 or newer (the toolchain is pinned to 17)
- No separate Gradle install needed — the wrapper (`./gradlew`, pinned to 9.3.1) handles it

## Run / Build

```bash
./gradlew :flow:run              # Run the desktop app
./gradlew :flow:compileKotlinJvm # Compile only
./gradlew :flow:packageDistributionForCurrentOs  # Native distribution package
```

## Project Structure

```
flow_editor/
├── settings.gradle.kts          # rootProject "flow", includes :flow
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle wrapper (9.3.1)
└── flow/
    ├── build.gradle.kts         # Kotlin Multiplatform + Compose + serialization
    └── src/
        ├── commonMain/kotlin/flow/
        │   ├── Model.kt         # Node / Edge / PortRef / FlowFile / SavedState / Sel
        │   ├── Registry.kt      # Module definitions (plugin registry) + ModuleDef
        │   ├── I18n.kt          # KO/EN dictionaries
        │   ├── Geometry.kt      # Grid snapping, port positions, bezier, default sizes
        │   ├── Palette.kt       # Design tokens (colors)
        │   ├── Platform.kt      # expect: persistence / file I/O / time
        │   ├── EditorState.kt   # State, history, simulation, wiring, auto-layout — all logic
        │   ├── Widgets.kt       # Txt / DtxField / hover helpers
        │   ├── App.kt           # Menu bar · sidebar · status bar · keyboard
        │   ├── CanvasView.kt    # Canvas · nodes · ports · edges · packets · minimap
        │   └── PropsPanel.kt    # Properties panel
        └── jvmMain/kotlin/flow/
            ├── Main.kt          # application entry point, window
            └── Platform.jvm.kt  # actual: ~/.flow/flow.json, AWT file dialogs
```

## Layout (single editor screen, 100vh)
1. **Menu bar** — logo, File/Edit/Window dropdowns, Start / Run Selection / Stop buttons, KO/EN toggle
2. **Left activity rail** — Project / Extensions buttons; each opens its panel, and pressing the button of the panel already showing collapses it
3. **Project panel** — the flows folder as a tree (see below)
4. **Module palette** — installed extension cards (drag onto the canvas)
5. **Canvas** — dotted grid background, nodes/edges/packets, minimap in the bottom-right
6. **Properties panel** — selected node (edit name/ID/params/ports) or edge (from → to, delete)
7. **Right activity rail** — Settings / Properties buttons, same toggle behaviour as the left rail

Both rails mark the cursor position with a faint wash on hover and keep the open one lit with an accent bar on the window edge.

## Project panel (file tree)
The panel shows `~/.flow/flows` as an IntelliJ-style tree: the root row carries the folder name with its full path greyed out beside it, and folders expand/collapse by their chevron (or a double-click). Which folders are open is remembered in the session.
- **Select** — click; Cmd/Ctrl+click extends the selection
- **Open** — double-click a flow file (a folder toggles instead)
- **Add** — the header's `+` creates a flow and the folder icon creates a folder, both inside the selected folder
- **Right-click menu** — New ▸ (Flow / Directory), Open, Rename, Delete, and Refresh on the root row; New puts the item in the clicked folder, or in the clicked file's folder
- **Shortcuts** — the menu shows each item's binding; ⌘N / ⌘⇧N create a flow or folder, F2 renames, ⌫ deletes the tree selection and ⌘, opens Settings (rename and delete apply while the tree is the panel last clicked in, so Delete still clears a canvas selection). All four are rebindable in Settings ▸ Keymap
- **Delete** — deleting a folder removes everything under it (after a confirmation) and closes any of its open tabs
- Every file in the folder is listed; anything that isn't a `.flow` file is greyed out and opening it reports an error instead of showing an empty canvas
- Files are addressed by their path relative to the flows root (`sub/dir/a.flow`), which is what open tabs, the session, and `comp:` component references all store
5. **Status bar** — interaction hints, module/connection counts, zoom controls, last auto-save time

## Behavior
- **Add a module**: drag a sidebar card onto the canvas — the drop point is converted to world coordinates and snapped to the grid
- **Drag / resize a node**: drag the header to move, drag the bottom-right handle to resize (min 120×60); both snap to the grid in real time and push a single history entry on release
- **Create a connection**: drag from an output port → dashed preview curve → release on an input port to create an edge (no self-connections; one edge per input port — an existing one is replaced)
- **Select / delete**: click nodes or edges to select, Delete/Backspace to remove, Escape to cancel a wire or close a menu
- **Pan / zoom**: Space + drag to pan, wheel to zoom about the cursor (0.3–2.5)
- **Undo / Redo**: Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y (snapshot stack, up to 60)
- **Run simulation**: starts from source nodes (no incoming edges) → run a node (900ms) → activate outgoing edges (850ms, packet animation) → next node. A node with input ports but no incoming connection becomes `error`. "Run Selection" starts directly from the selected node (input check skipped). The run ends automatically once no timers remain
- **Auto-layout** (Edit menu): BFS depth-based column layout (x = 60 + depth·280)
- **Localization**: KO/EN toggle for the whole UI, selected language is persisted

## State & Persistence
- `Node{id, type, label, x, y, w, h, inputs[], outputs[], params, status}` / `Edge{id, from, to, active}`
- Renaming a node id syncs every referencing edge; port rename/removal behaves the same way
- Auto-save: state changes are debounced by 350ms and written to `~/.flow/flow.json`
- JSON export: `{version, nodes, edges, seq}` / import via an AWT file dialog

## Module Definitions (plugin registry)
The `ModuleDef` array in [Registry.kt](flow/src/commonMain/kotlin/flow/Registry.kt) is the plugin list — external plugins appear in the sidebar once registered here.
- Built-in: csv(0→1), filter(1→2: pass/fail), map(1→1), merge(2→1: a,b), split(1→2), agg(1→1), log(1→0), fout(1→0)
- Example plugins: jflat (JSON flatten), regex (regex extract)
- Categories: source / transform / sink

## Design Tokens
Colors, spacing, and typography reproduce the original design spec pixel for pixel. Color tokens are defined in [Palette.kt](flow/src/commonMain/kotlin/flow/Palette.kt).
- Backgrounds: app #14161B · panel #1B1E26 · canvas #101218 · node #1D212B
- Accents: #5B8CFF · success #34C98E · error #FF5C5C · categories #22C3A6 / #B07BFF / #FF9D5C
- Grid unit 20, node/card radius 7–9, button radius 5–6
- Fonts: system sans-serif (UI) + monospace (ids and code values)

## Terminal CLI

Besides the UI, a component can be executed from the terminal: pick a component (a flow with input/output boundary nodes) and feed it input; it evaluates the graph and prints the output. Backed by a UI-independent execution engine (`flow.engine`).

```bash
./gradlew :flow:cli --args="--list"            # list available components
./gradlew :flow:cli --args="triple 5"          # single input → out = 15
./gradlew :flow:cli --args="double --in in=10" # per-port input → out = 20
```

Components are read from `~/.flow/flows` (by name) or a file path. The engine evaluates nodes in topological order; `map`/`filter` expressions are handled by a small evaluator (arithmetic, comparisons, variable `x`/`value`), and nested `comp:` nodes are expanded recursively.

## Extensions

Extensions provide **modules only** — components are built inside the Flow tool and added there. A module extension is identified by a **package-format id**, declares input/output ids, and may declare typed options (text / number / select) shown in the property panel. Its ports carry **bytes**. Both the ports and the options on show can depend on the current option values (`inputsFor` / `outputsFor` / `optionsFor`) — `flow.keyfactory` swaps its ports between `password`+`salt` and `key`, and hides the options its algorithm doesn't use. The `flow-extension-api` module defines the contract:

- **Module extension** (`ModuleExtension`) — the implementation: `process(inputs: bytes, options) → outputs: bytes`. Distributed as code (a JAR), registered under `META-INF/services/flow.extension.ModuleExtension`.

Nothing ships with the app — the palette starts empty and every module arrives by being installed
from **Settings ▸ Extensions**. The published set covers the JCA facilities plus a few utilities:
`flow.hash`, `flow.mac`,
`flow.signature`, `flow.cipher` (symmetric algorithms, and RSA with PKCS#1 or OAEP padding),
`flow.keygen`, `flow.keypairgen`, `flow.keyfactory` (PBKDF2 derivation, and PKCS#8 / X.509 /
certificate keys as DER or PEM), `flow.keystore` (PKCS#12 and JKS stores → private key,
certificate, public key), `flow.securerandom`, `flow.base64` (standard or URL-safe alphabet, with
or without padding), `flow.slice` (a byte range and the remainder — how a prepended IV is taken
off a ciphertext), `flow.merge`, `flow.split`, `flow.sleep` and `flow.mcp`.

First-party extensions live under `flow-extensions/` using `flow.*` package ids. They are built the
same way anyone else's would be and published to the registry — the app has no privileged set.

### Storage & sandbox (installed, read-only)
Everything installed lives under `~/.flow/extensions/<id>/`, keyed by id, each in **its own folder**
with its dependency JARs bundled alongside — either a module extension's jar(s), or a component's
`component.json` plus the module jars it depends on. What makes a folder a component is the presence
of `component.json`; without that distinction a component's bundled dependency would register itself
as an installed module. Each runs in a **sandbox**: an isolated classloader over just its own
folder's JARs, so one extension's dependencies never clash with another's.

Installed items are read-only; editing one and saving writes a **separate file** into `flows/`.

The rest of the app data dir: `~/.flow/flows/` is the project, `~/.flow/settings.json` holds the
preferences Settings edits, and `~/.flow/session.json` the open tabs and window layout.

### Installing
**Extensions** (logo menu) lists what the registry offers with **Install**, or **Update** when it
carries a newer `version` than the installed one, and **Uninstall** on anything already installed.
The same list covers what was installed from a file rather than the registry, so nothing becomes
unremovable. Above it, **Install extension from file…** takes a jar and **Install flow from file…**
registers a `.flow` as a component. If an id already exists you're asked to **overwrite**.

The registry is a JSON manifest served over HTTPS —
[siprikorea/flow-extensions](https://github.com/siprikorea/flow-extensions) by default, changed via
`registryUrl` in `settings.json`. From the terminal:

```bash
./gradlew :flow-extensions:base64-extension:jar
./gradlew :flow:cli --args="--install /abs/path/base64-extension.jar"   # add --force to overwrite
./gradlew :flow:cli --args="--mcp"                                     # MCP server on stdio
```

Modules: `flow-extension-api` (contract), `flow` (editor + CLI + install/registry), `flow-extensions/base64-extension` (`flow.base64`, with an encode/decode option), `flow-extensions/mcp-extension` (`flow.mcp`, calls a tool on an external MCP server) and `flow-extensions/sample-extension` (`com.example.mul3`, `com.example.upper`).

## MCP

Flow speaks the Model Context Protocol in both directions, with no extra dependencies — the
JSON-RPC 2.0 stdio plumbing is implemented in the repo.

### Flow as an MCP server
`cli --mcp` lets a client such as Claude Desktop or Claude Code **author** flow files: describe what
a flow should do and it is drafted from the installed modules, wired up and laid out. Running flows
is the app's job, so the server exposes five tools and nothing else:

| Tool | |
|---|---|
| `list_modules` | every building block — cin/cout, each module's ports and options, existing flows usable as sub-components |
| `list_flows` | the project's flow files with their ports |
| `read_flow` | a flow as the same `{nodes, edges}` spec `build_flow` takes, plus its `problems` |
| `validate_flow` | verify a saved flow and report every fault found |
| `build_flow` | build a `.flow` from that spec and return its contents |

`build_flow` takes the graph, not the file format: nodes by type, edges as `"node.port"` (or just
`"node"` when that side has one port). Port lists, edge ids and node sizes are worked out server
side — a module's ports can depend on its options, so they are asked for rather than assumed.
Coordinates are optional: set `x`/`y` to place a node deliberately, or leave them off and the graph
is laid out left to right with each node centred on whatever feeds it.

**Nothing is written.** `build_flow` hands back the file's contents and stops there; what goes into
the project is the user's call, so they save it and bring it in through **File ▸ Open File…** or by
dropping it on the window. Editing an existing flow is therefore `read_flow` → change the spec →
`build_flow` → save over it. `~/.flow/flows` is only ever read by this server.

Both `read_flow` and `validate_flow` report the faults that can be established structurally: a
module that is not installed, an option set to a value it does not accept, ports that no longer
match a node's options, an edge to a port that isn't there, an input fed twice or not at all, a
loop in the wiring, a missing `cin`/`cout`.

A client needs a plain command, and gradle's own output would corrupt the protocol stream, so
generate a launcher that has the classpath baked in:

```bash
./gradlew :flow:mcpLauncher      # writes flow/build/flow-mcp
claude mcp add flow -- /abs/path/to/flow/build/flow-mcp
```

The equivalent entry for a `mcpServers` config block is
`{"flow": {"command": "/abs/path/to/flow/build/flow-mcp"}}`. Tools are rebuilt per request, so a
flow saved while the server runs shows up without a restart. Only protocol messages go to stdout;
logs go to stderr.

### Claude Desktop extension (.mcpb)
For Claude Desktop the server can ship as an extension bundle instead of a config entry:

```bash
./gradlew :flow:mcpbBundle          # writes flow/build/flow.mcpb
```

That one task runs all three steps: `mcpbStage` lays out `flow/build/mcpb` (manifest + server jars),
`mcpbBundleVerify` drives the server staged there and checks it answers,
then the zip itself is done by `npx -y @anthropic-ai/mcpb pack` — so Node has to be on `PATH`.

Install the resulting `.mcpb` from **Settings ▸ Extensions ▸ Advanced settings ▸ Install Extension…**.
Without Node, run `:flow:mcpbStage` alone and point **Install Unpacked Extension** at
`flow/build/mcpb` — same layout, just not zipped. That directory is build output, so `./gradlew
clean` removes it.
The manifest and the launcher live in [flow/mcpb/](flow/mcpb); the bundle carries no extensions of
its own, reading both flows and installed extensions out of `~/.flow` as the app does. Claude Desktop bundles a Node runtime but no JVM,
so the launcher resolves a JDK 17+ from `JAVA_HOME`, then `/usr/libexec/java_home`, then `PATH` — one
has to be installed on the machine. Only the jars the MCP path actually loads are staged (no Compose
or Skiko), which keeps the bundle near 3MB.

### Calling an MCP tool from a flow
The `flow.mcp` module ("MCP Tool") runs an MCP server as a child process and calls one of its
tools. `command` is the server command line, `tool` the tool to call, `argument` the argument the
`in` port feeds (default `input`), and `arguments` any further arguments as a JSON object; the
tool's text content lands on `out`. Leaving `tool` blank lists the server's tools instead — the
module drops its `in` port and the argument options in that mode.

## Notes
The numeric specs — grid snapping, port placement, bezier curves, simulation timings — come from the original HTML design prototype. This repository is a Compose Multiplatform reimplementation of that spec.
