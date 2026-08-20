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
4. **Module palette** — built-in modules + installed plugin cards (drag onto the canvas)
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

First-party (built-in) extensions live under `flow-extensions/` using `flow.*` package ids; `flow-extensions/sample-extension` is a third-party example under `com.example.*`.

### Storage & sandbox (installed, read-only)
Installed modules live under the app data dir, keyed by id, each in **its own folder** with its dependency JARs bundled alongside:
- `~/.flow/modules/<id>/*.jar` — module extension + its dependency modules.

Tool-made components you install go to `~/.flow/components/<id>/component.json` (+ bundled dependency module JARs). Each module and each component runs in a **sandbox**: an isolated classloader over just its own folder's JARs, so one extension's dependencies never clash with another's.

Installed items are read-only; editing one and saving writes a **separate file** into `flows/`.

### Installing
Use the **Flow (logo) menu → Install Extension…** to pick a JAR, or **Install Current Component** to install the open editor component. If the id already exists, you're asked to **overwrite**. From the terminal:

```bash
./gradlew :flow-extensions:base64-extension:jar
./gradlew :flow:cli --args="--install /abs/path/base64-extension.jar"   # add --force to overwrite
```

Modules: `flow-extension-api` (contract), `flow` (editor + CLI + install/registry), `flow-extensions/base64-extension` (`flow.base64`, with an encode/decode option) and `flow-extensions/sample-extension` (`com.example.mul3`, `com.example.upper`).

## Notes
The numeric specs — grid snapping, port placement, bezier curves, simulation timings — come from the original HTML design prototype. This repository is a Compose Multiplatform reimplementation of that spec.
