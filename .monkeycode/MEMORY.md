# User Instruction Memory

This file records user instructions, preferences, and project knowledge for future interactions.

## Format

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Entries

[Project Knowledge Summary]
- Date: 2026-09-16
- Context: Discovered while building OpenMinis-Linux Android devstack release
- Category: Build Methods
- Instructions:
  - Release CI file: `.github/workflows/build-and-release.yml`. Triggered by tag push (`v*`) or manual `workflow_dispatch`.
  - Publishing a GitHub Release requires a tag push (the "Publish GitHub Release" step has `if: startsWith(github.ref, 'refs/tags/')`). `workflow_dispatch` builds + uploads artifact but SKIPS the release publish.
  - To release: `git tag vX.Y.Z-<suffix> && git push origin vX.Y.Z-<suffix>` then wait for the run.
  - Build deps in CI: proot native build (deps/build_proot.sh), rclone Android AAR (needs Go 1.25 from go.dev, gomobile), devstack rootfs asset (scripts/prepare_devstack_rootfs.sh → copies build/ubuntu-noble-aarch64.tar.gz to assets).
  - Debug/coexistence: devstack applicationId = `app.openminis.devstack` (differs from original `com.openminis.app`). Native offload abstract socket name = `native-offload.<applicationId>`. Debug server port offset +1000 (6321).
  - GH CLI auth token is available at `/tmp/github_token`; export GH_TOKEN=$(cat /tmp/github_token) before `gh` commands.

[Project Knowledge Summary]
- Date: 2026-09-16
- Context: Discovered while fixing Kotlin compilation errors in release build
- Category: Troubleshooting & Debugging
- Instructions:
  - When fixing Kotlin "Unresolved reference" / import errors in this Android project, the root cause is usually a missing import statement (not a real logic bug). Check the file's import block first.
  - `BuildConfig` import conflicts with `Icons.Outlined.Build`: import `BuildConfig` under an alias (e.g. `import com.openminis.app.BuildConfig as AppBuildConfig`) and add a separate `import androidx.compose.material.icons.outlined.Build`.
  - Compose `by remember { mutableStateOf(...) }` delegates require BOTH `import androidx.compose.runtime.getValue` and `import androidx.compose.runtime.setValue` alongside mutableStateOf/remember.
  - `withContext(Dispatchers.IO)` cannot be used inside a non-suspend function (NativeOffloadHandler.handle is NOT suspend). Replace with direct blocking calls or make the helper `suspend` and call from a coroutine scope.
  - Abstract socket name constant must be injected at build time for the C proot fork; Kotlin `SOCKET_NAME` derives from BuildConfig.APPLICATION_ID at runtime.

[Project Knowledge Summary]
- Date: 2026-09-16
- Context: Discovered while removing MonkeyCode AI co-author lines from git history
- Category: Workflow & Collaboration
- Instructions:
  - User requires NO "Co-authored-by: monkeycode-ai <monkeycode-ai@chaitin.com>" attribution in commits. Remove it via `git filter-branch -f --msg-filter 'sed "/^Co-authored-by: monkeycode-ai/d"' -- --all` then force-push.
  - Do not re-add the co-author line in future commits for this project.
