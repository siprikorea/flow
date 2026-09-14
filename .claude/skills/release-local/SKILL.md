---
name: release-local
description: Build a Flow release on this machine and publish it, when CI cannot. Use when a tag is pushed but the build never ran (no runner, zero steps, Actions minutes or a GitHub outage), or when asked to release without CI ("로컬에서 빌드해서 배포", "CI 없이 배포", "publish from here"). Covers what CI produces, how to make the same thing locally, and the checks that replace the ones CI was doing.
---

# Releasing Flow from this machine

**CI is the way a release goes out.** This is for when it cannot: the job never starts (no runner
assigned, zero steps, ten seconds — that is the Actions quota or an outage, not the code), and a
release has to reach people anyway.

What changes is who runs the build. What does not change is the tag, the assets, or the checks —
this produces the same release CI would have, or it is not worth doing.

## 0. Be sure it is CI that is broken

A red build is not this. Look at the job before deciding:

```bash
tok=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
run=$(curl -s -H "Authorization: Bearer $tok" \
  "https://api.github.com/repos/siprikorea/dataflow-editor/actions/runs?per_page=1" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['workflow_runs'][0]['id'])")
curl -s -H "Authorization: Bearer $tok" \
  "https://api.github.com/repos/siprikorea/dataflow-editor/actions/runs/$run/jobs" \
  | python3 -c "
import json,sys
j=json.load(sys.stdin)['jobs'][0]
print(j['started_at'], '->', j['completed_at'], '| runner', repr(j['runner_name']), '| steps', len(j.get('steps',[])))"
```

- **steps > 0** — the build ran and something failed. Fix that; do not publish around it.
- **steps 0, no runner, a few seconds** — the job was refused before it started. That is what this
  skill is for. Say so plainly to the user, and that the usual cause on a private repo with
  `macos-latest` is the Actions allowance (macOS minutes bill at 10×); the billing page is theirs
  to check, not something the API will answer without the scope.

## 1. What a release is

CI publishes these to **`siprikorea/flow`** (public), from the private source repo. Anything
missing here is a release that looks fine and is not:

| Asset | Where it comes from | What needs it |
|---|---|---|
| `Flow-X.Y.Z.dmg` | `:flow:packageDmg -PflowVersion=X.Y.Z` | the download, and the in-app updater |
| `flow.mcpb` | `:flow:mcpbBundle` | Claude Desktop's extension install |
| `<name>-module.jar` ×26 | `flow-modules/*/build/libs/` | the Modules screen's Install |
| `extensions.json` | a copy of `flow-modules/registry.json` | **the app reads this by URL** |

`latest` moves only on a `v*` tag, and the app's registry and its updater both read
`releases/latest/download/…`. A prerelease does not move it.

## 2. Build from the tag, not from the tree

CI checks out the tag into an empty directory. This machine has whatever you were doing, and both
of these have shipped from a local build before: an uncommitted edit, and a stale jar in
`build/libs` from a module that has since been renamed.

```bash
git status --short | grep -v '^??'          # must be empty
git switch --detach vX.Y.Z                  # build what the tag says, not what is open
./gradlew clean                             # stale jars are how a renamed module ships twice
./gradlew build                             # the tests CI would have run
./gradlew build -PflowTestHome=/tmp/empty-home   # and the way CI sees them: no modules installed
```

The second test run matters: tests that read `~/.flow` pass here and fail on a clean checkout,
which is exactly what CI would have caught.

Then the packaging, with the version **passed in** — without it the bundle says `1.0.0`, the
updater compares that against the release and offers an update to everyone forever — and on the
same JDK CI uses, or the app ships a different JVM than every release before it:

```bash
JAVA_HOME=/path/to/temurin-25 ./gradlew \
  -Porg.gradle.java.installations.paths=/path/to/temurin-25 \
  -Porg.gradle.java.installations.auto-detect=false \
  :flow:packageDmg :flow:mcpbBundle -PflowVersion=X.Y.Z
```

CI runs on Temurin 25 (`setup-java`, `temurin`); this machine's only Java 25 is the JetBrains
Runtime that came with the IDE, and Gradle finds it through `JAVA_HOME`. The bundled runtime is
jlinked from whichever JDK Gradle runs on and `bin/java` is copied from whichever the toolchain
resolves, so leaving either to chance ships JBR inside the app — 10MB larger, and the JBR window
decorations the app checks for suddenly active for the people on that one release. Get it from
`https://api.adoptium.net/v3/binary/version/jdk-25.0.4.1%2B1/mac/aarch64/jdk/hotspot/normal/eclipse`
and check what landed:

```bash
flow/build/compose/binaries/main/app/Flow.app/Contents/runtime/Contents/Home/bin/java -version
# OpenJDK Runtime Environment Temurin-25.0.4.1+1 — not JBR
```

`rm -rf flow/build/compose` before a rebuild: the runtime image is up-to-date as far as Gradle is
concerned, so a second attempt with a different JDK quietly reuses the first one's.

## 3. Collect and check before anything is uploaded

```bash
mkdir -p dist && rm -f dist/*
cp "$(ls -t flow/build/compose/binaries/main/dmg/*.dmg | head -1)" dist/
cp flow/build/flow.mcpb dist/
cp flow-modules/*/build/libs/*.jar dist/
cp flow-modules/registry.json dist/extensions.json

# the check CI fails the deploy on: every jar listed, every listed jar built
built=$(cd flow-modules && ls */build/libs/*.jar | xargs -n1 basename | sort)
listed=$(python3 -c "import json;print('\n'.join(sorted(e['file'] for e in json.load(open('flow-modules/registry.json'))['extensions'])))")
[ "$built" = "$listed" ] || diff <(echo "$listed") <(echo "$built")
```

And the three things a local build can get wrong that CI cannot:

```bash
ls dist/Flow-X.Y.Z.dmg                                  # named for the tag, not the last build
hdiutil attach dist/Flow-X.Y.Z.dmg -nobrowse -quiet -mountpoint /tmp/rel
plutil -p /tmp/rel/Flow.app/Contents/Info.plist | grep -i shortversion   # says X.Y.Z
codesign --verify --deep --strict /tmp/rel/Flow.app                      # signed
ls /tmp/rel/Flow.app/Contents/runtime/Contents/Home/bin/java             # the launcher jpackage strips
lipo -archs /tmp/rel/Flow.app/Contents/MacOS/Flow                        # arm64 — an Intel Mac ships the wrong app
/tmp/rel/Flow.app/Contents/runtime/Contents/Home/bin/java -version       # Temurin, as every other release
hdiutil detach /tmp/rel -quiet
```

## 4. Publish

`gh` is not installed here; the REST API is the path. The token must be able to write to
**`siprikorea/flow`** — the stored credential is for the source repo and may not be the same one CI
uses (`FLOW_RELEASE_TOKEN`). Check before uploading, or the first failure comes halfway through
twenty-nine assets:

```bash
tok=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
curl -s -H "Authorization: Bearer $tok" https://api.github.com/repos/siprikorea/flow \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['permissions'])"   # push must be true
```

Create the release, then upload every file:

```bash
sha=$(git rev-parse --short HEAD)
id=$(curl -s -X POST -H "Authorization: Bearer $tok" \
  https://api.github.com/repos/siprikorea/flow/releases \
  -d "{\"tag_name\":\"vX.Y.Z\",\"name\":\"Flow vX.Y.Z\",\"body\":\"Built from siprikorea/dataflow-editor@$sha, locally.\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

for f in dist/*; do
  curl -s -X POST -H "Authorization: Bearer $tok" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" \
    "https://uploads.github.com/repos/siprikorea/flow/releases/$id/assets?name=$(basename "$f")" \
    > /dev/null || echo "FAILED $f"
done
```

Say in the release body that it was built locally. Someone reading the release later needs to know
it did not come from a clean checkout with the tests green in front of it.

If the release already exists (CI made it and only some assets landed), upload with the same call —
an asset of the same name is refused, so delete that one first:
`DELETE /repos/siprikorea/flow/releases/assets/{asset_id}`.

## 5. After

```bash
git switch main                                          # off the detached tag
curl -s https://api.github.com/repos/siprikorea/flow/releases/latest \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['tag_name'], len(d['assets']))"
curl -sL https://github.com/siprikorea/flow/releases/latest/download/extensions.json \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)['extensions']), 'entries')"
```

29 assets, and the manifest reachable at the URL the app reads. Then verify the artifact the way
the `release` skill does — download the published .dmg, launch it, and check the module workers
start — because nothing else has run it.

Finally: **put CI back in the path.** A local release is a one-off. When the runners are back, push
something small to `main` and confirm a build goes green, so the next release is CI's again.
