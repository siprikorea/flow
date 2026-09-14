---
name: release
description: Cut and verify a Flow release. Use when asked to deploy, publish, ship, tag, or release Flow (e.g. "배포해줘", "v1.7.0으로 배포", "release this"). Covers the pre-flight checks that fail the deploy, the two places a version lives, and the post-release verification that catches what CI cannot.
---

# Releasing Flow

Publishing is a push. CI (`.github/workflows/build.yml`) builds on macOS, runs the tests, and
publishes to **`siprikorea/flow`** — the public download repo; the source repo stays private.

- **push to `main`** → rolling `nightly` prerelease
- **push a `v*` tag** → a real release, and `latest` moves to it

If the build never starts — no runner, zero steps, ten seconds — that is not the code, and
`release-local` is the way to get the same release out from this machine.

**`latest` only moves on a tag.** The app reads its extension registry from
`releases/latest/download/extensions.json`, and GitHub's `latest` alias skips prereleases — so a
change to an extension or the manifest reaches users **only when a `v*` tag is cut**. A nightly is
for downloading a jar by hand, nothing more. This has confused a release before.

## 1. Before pushing

Run all four. The third is the one that fails the deploy in CI, minutes in, after the tests pass.

```bash
git status --short | grep -v '^??'          # must be empty
./gradlew build                              # tests included
# CI's own check: every built jar is listed, and every listed jar is built
built=$(cd flow-modules && ls */build/libs/*.jar | xargs -n1 basename | sort)
listed=$(jq -r '.extensions[].file' flow-modules/registry.json | sort)
[ "$built" = "$listed" ] && echo OK || diff <(echo "$listed") <(echo "$built")
git log --oneline origin/main..main          # what is about to go
```

### If an extension changed

Its version lives in **two** places and both must move, or the app offers no update and users keep
the old jar:

1. `flow-modules/<name>-extension/src/main/kotlin/.../XExtension.kt` — `override val version`
2. `flow-modules/registry.json` — that entry's `"version"`

An extension whose behaviour changed but whose version did not is invisible: `registryState`
compares the two numbers, finds them equal, and offers nothing.

### If the extension API changed

`flow-module-api` is an ABI. Extensions are jars users already have, compiled against whatever
it looked like then.

- **Additive only**: a new interface member with a default. Never a new parameter on
  `ExtensionOption` or any other existing signature.
- `ExtensionAbiTest` pins this. If it fails, the change breaks every installed extension — v1.5.0
  shipped exactly that (`NoSuchMethodError: ExtensionOption.<init>`) and needed v1.5.1 to undo it.
- Adding a parameter also breaks *forwards*: jars built against the broken version stop working
  when it is reverted. Bump those extensions so an update is offered.

### If the app's own code changed

Two things CI cannot catch, because it compiles everything from source and never installs anything:

- **A new JDK module.** The runtime jpackage bundles contains only what `nativeDistributions {
  modules(...) }` lists. Using something outside it is a `NoClassDefFoundError` at startup from the
  .dmg while Gradle runs fine. Check with `./gradlew :flow:suggestRuntimeModules` and add what it
  names.
- **Anything that spawns a JVM.** `restoreRuntimeLauncher` puts `bin/java` back into the packaged
  runtime, which jpackage strips; without it every extension worker and the MCP server fails to
  start in an installed app, silently. If packaging changes, re-verify (step 4).

## 2. Push and tag

```bash
git push origin main
git tag -a vX.Y.Z -m "vX.Y.Z

<what changed, in the terms a user would notice>"
git push origin vX.Y.Z
```

Version: patch for a fix, minor for a feature or a new extension. Previous tags are annotated; keep
that.

## 3. Wait for the release, and check what is in it

CI takes roughly 5–10 minutes.

```bash
until curl -s "https://api.github.com/repos/siprikorea/flow/releases/tags/vX.Y.Z" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
if 'assets' not in d: print('waiting'); raise SystemExit(1)
n=[a['name'] for a in d['assets']]
print('assets', len(n), '| dmg', [x for x in n if x.endswith('.dmg')])
raise SystemExit(0 if any(x.endswith('.dmg') for x in n) else 1)"; do sleep 45; done
```

Then confirm the app-facing manifest actually moved — this is the step that tells you whether users
will see the change at all:

```bash
curl -sL "https://github.com/siprikorea/flow/releases/latest/download/extensions.json" \
 | python3 -c "
import json,sys,collections
d=json.load(sys.stdin)
print('entries', len(d['extensions']), dict(collections.Counter(e['category'] for e in d['extensions'])))
"
```

If the CI run is not visible, the source repo is private — query it with the stored git credential:

```bash
tok=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
curl -s -H "Authorization: Bearer $tok" \
  "https://api.github.com/repos/siprikorea/dataflow-editor/actions/runs?per_page=3"
```

## 4. Verify the artifact, not just the release

CI going green means the code compiles and the tests pass. It says nothing about whether the thing
people download runs — twice now it did not. For any release touching the app or packaging, spend
the two minutes:

```bash
curl -sL -o /tmp/Flow.dmg "https://github.com/siprikorea/flow/releases/download/vX.Y.Z/Flow-X.Y.Z.dmg"
hdiutil attach /tmp/Flow.dmg -nobrowse -quiet -mountpoint /tmp/flowmnt
APP=/tmp/flowmnt/Flow.app
ls "$APP/Contents/runtime/Contents/Home/bin/java"        # the stripped launcher, put back
codesign --verify --deep --strict "$APP" && echo "signature ok"
cp -R "$APP" /tmp/ && hdiutil detach /tmp/flowmnt -quiet
/tmp/Flow.app/Contents/MacOS/Flow &                       # must stay up
sleep 14; pgrep -f ModuleWorker | wc -l                # must be > 0, or no extension works
kill %1; pkill -f ModuleWorker
```

`~/.flow/app.pid` is shared with any running instance — back it up and restore it if a test launch
would clobber it.

## 5. Report

Say the tag, what `latest` now serves, and what the user has to do: update the app, then
**Settings ▸ Modules ▸ Refresh** before a new or updated extension appears. An
extension that declares settings needs *both* halves updated — the app reads the declaration, the
jar makes it.

Be explicit about what was not verified. Live-service paths (Slack, Telegram, OpenAI/Gemini CLIs)
are tested against local servers or not at all; say so rather than implying they were exercised.
