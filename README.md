# DataFlow Editor

A visual data-transformation pipeline editor. Drag modules (sources, transforms, sinks) onto a canvas, connect output ports to input ports with bezier curves, and build a data flow. Start/stop a run simulation (packets animate along the curves while node statuses update), and persist flows via auto-save and JSON export/import.

Built with **Compose Multiplatform** (Kotlin). All UI, state, and logic live in `commonMain`; `jvmMain` only provides the entry point and file I/O. It currently runs on the desktop (JVM) target.

## Requirements
- JDK 17 or newer (the toolchain is pinned to 17)
- No separate Gradle install needed — the wrapper (`./gradlew`, pinned to 9.3.1) handles it

## Run / Build

```bash
./gradlew :composeApp:run              # Run the desktop app
./gradlew :composeApp:compileKotlinJvm # Compile only
./gradlew :composeApp:packageDistributionForCurrentOs  # Native distribution package
```

## Project Structure

```
dataflow_editor/
├── settings.gradle.kts          # rootProject "dataflow-editor", includes :composeApp
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle wrapper (9.3.1)
└── composeApp/
    ├── build.gradle.kts         # Kotlin Multiplatform + Compose + serialization
    └── src/
        ├── commonMain/kotlin/dataflow/
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
        └── jvmMain/kotlin/dataflow/
            ├── Main.kt          # application entry point, window
            └── Platform.jvm.kt  # actual: ~/.dataflow-editor/flow.json, AWT file dialogs
```

## Layout (single editor screen, 100vh)
1. **Menu bar** — logo, File/Edit/Window dropdowns, Start / Run Selection / Stop buttons, KO/EN toggle
2. **Module list sidebar** — built-in modules + installed plugin cards (drag onto the canvas)
3. **Canvas** — dotted grid background, nodes/edges/packets, minimap in the bottom-right
4. **Properties panel** — selected node (edit name/ID/params/ports) or edge (from → to, delete)
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
- Auto-save: state changes are debounced by 350ms and written to `~/.dataflow-editor/flow.json`
- JSON export: `{version, nodes, edges, seq}` / import via an AWT file dialog

## Module Definitions (plugin registry)
The `ModuleDef` array in [Registry.kt](composeApp/src/commonMain/kotlin/dataflow/Registry.kt) is the plugin list — external plugins appear in the sidebar once registered here.
- Built-in: csv(0→1), filter(1→2: pass/fail), map(1→1), merge(2→1: a,b), split(1→2), agg(1→1), log(1→0), fout(1→0)
- Example plugins: jflat (JSON flatten), regex (regex extract)
- Categories: source / transform / sink

## Design Tokens
Colors, spacing, and typography reproduce the original design spec pixel for pixel. Color tokens are defined in [Palette.kt](composeApp/src/commonMain/kotlin/dataflow/Palette.kt).
- Backgrounds: app #14161B · panel #1B1E26 · canvas #101218 · node #1D212B
- Accents: #5B8CFF · success #34C98E · error #FF5C5C · categories #22C3A6 / #B07BFF / #FF9D5C
- Grid unit 20, node/card radius 7–9, button radius 5–6
- Fonts: system sans-serif (UI) + monospace (ids and code values)

## Terminal CLI

Besides the UI, a component can be executed from the terminal: pick a component (a flow with input/output boundary nodes) and feed it input; it evaluates the graph and prints the output. Backed by a UI-independent execution engine (`dataflow.engine`).

```bash
./gradlew :composeApp:cli --args="--list"            # list available components
./gradlew :composeApp:cli --args="triple 5"          # single input → out = 15
./gradlew :composeApp:cli --args="double --in in=10" # per-port input → out = 20
```

Components are read from `~/.dataflow-editor/flows` (by name) or a file path. The engine evaluates nodes in topological order; `map`/`filter` expressions are handled by a small evaluator (arithmetic, comparisons, variable `x`/`value`), and nested `comp:` nodes are expanded recursively.

## Plugins

Plugins come in two kinds, both identified by a **package-format id** and declaring input/output ids. The `plugin-api` module defines the contracts:

- **Module plugin** (`ModulePlugin`) — has the actual implementation: `process(inputs) → outputs`. Distributed as code (a JAR).
- **Component plugin** (`ComponentPlugin`) — defines only the connections between modules, with no coordinates. On install it is laid out automatically (BFS columns) into a component flow with input/output boundary nodes.

### Storage & sandbox (installed, read-only)
Installed artifacts live under the app data dir, separated by kind, keyed by id, each in **its own folder** with its dependency JARs bundled alongside:
- `~/.dataflow-editor/modules/<id>/*.jar` — module plugin + its dependency modules.
- `~/.dataflow-editor/components/<id>/component.json` (+ bundled dependency module JARs).

Each module and each component runs in a **sandbox**: an isolated classloader over just its own folder's JARs. So one plugin's dependencies never clash with another's, and a component executes using the modules bundled in its folder — fully independent of what's installed globally.

Installed items are read-only; editing one and saving writes a **separate file** into `flows/`.

### Installing
Use the **DataFlow (logo) menu → Install Plugin…** to pick a JAR, or **Install Current Component** to install the open editor component. If the id already exists, you're asked to **overwrite**. From the terminal:

```bash
./gradlew :plugins:sample-plugin:jar
./gradlew :composeApp:cli --args="--install /abs/path/sample-plugin.jar"   # add --force to overwrite
./gradlew :composeApp:cli --args="com.example.triple 5"                    # out = 15 (via the mul3 module)
```

Modules: `plugin-api` (contracts), `composeApp` (editor + CLI + install/registry), `plugins/sample-plugin` (a `ModulePlugin` `com.example.mul3` and a `ComponentPlugin` `com.example.triple`).

## Notes
The numeric specs — grid snapping, port placement, bezier curves, simulation timings — come from the original HTML design prototype. This repository is a Compose Multiplatform reimplementation of that spec.
