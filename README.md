# UnrealHelper

UnrealHelper is a Rider plugin for coordinating Unreal Engine build, cook, package, and direct-launch workflows from a shared Target & Platform configuration.

## Target & Platform Rows

Shared configurations live in `.unrealhelper/target-platforms.json`. Each row selects:

```text
Build Target | Platform | Arguments | Cook | Incremental Cook
```

- **Build Target** is an exact discovered Unreal target such as `Lyra`, `LyraClient`, or `LyraServer`; its Game, Client, or Server type is inferred from discovery.
- **Arguments** apply only to that row's launched process. The current toolbar global arguments are appended after them.
- **Cook** requests a cook before Launch for that row.
- **Incremental Cook** requests UAT incremental cooking and is available only when Cook is enabled. Unreal may invalidate prior data and perform a full cook internally.

Missing or renamed targets remain visible for repair. Workflow preflight blocks invalid rows instead of silently replacing them. Executable and working-directory overrides are not part of the shared format.

## Toolbar Workflows

Every toolbar request is planned completely before execution, deduplicated by exact artifact identity, and run in phase order:

```text
Build -> Cook -> Stage -> Package -> Launch
```

Empty phases are omitted.

- **Build** builds every unique target/platform artifact in one compatible UnrealBuildTool batch and ignores row Cook flags.
- **Cook** cooks every unique artifact and honors each row's Incremental Cook selection. It does not build, stage, or package.
- **Package** builds, fully cooks, stages, and packages every unique artifact beneath an isolated target directory in the configured package directory. Cook, staging, and archive state is never shared between exact targets.
- **Launch** builds every unique artifact, cooks only rows with Cook enabled, and launches every row. Launch never stages or packages.

Repeated rows intentionally produce repeated Launch processes, while their identical build and cook prerequisites collapse. A full cook supersedes an otherwise identical incremental cook. Game, Client, and Server cooks remain distinct, as do different exact target names.

## Direct Launch

After Build succeeds, Launch resolves the exact target receipt for the target name, platform, build configuration, and architecture when known. The receipt's `Launch` product is authoritative; receipt macros and project-relative paths are resolved against the configured engine and project roots. Engine-shared launch products receive the project path when required.

The freshly compiled receipt executable starts directly. With Cook enabled, UAT writes to a target- and role-specific cook output and the executable receives that loose cooked directory as its `-sandbox`; with Cook disabled it starts immediately after Build without a sandbox argument. Launch does not copy binaries, stage content, create a package, or search archived packages. If no matching receipt exists—or multiple architectures match while architecture is unknown—the workflow fails rather than guessing an executable.

## Progress, Failure, And Restart

Each request appears as one session in Rider's **Build** tool window, with phase and action nodes, state transitions, and raw UBT/UAT output. Launched games are tracked separately in the **Run** tool window and remain independently stoppable after the workflow session completes.

Non-launch actions run sequentially. Launch processes are handed off to the running-launch registry as soon as each process starts, allowing the remaining launch rows to start without waiting for games to exit. If an action fails, every not-yet-started action is cleared and no additional launches start; already-started games are left running and tracked.

Requesting Build, Cook, Package, or Launch while queue work or an artifact-conflicting game is active opens a confirmation with the running, queued, and conflicting launch entries:

- **Stop and Restart** stops queue-owned work and conflicting games, waits for every stop to complete, then starts the new plan automatically.
- **Keep Running**, closing, or cancelling the dialog discards the new request and preserves existing work.
- If any process cannot be stopped, the replacement plan does not start.

See [the design](docs/design.md) for the complete behavior and [manual validation](docs/manual-validation.md) for the approved Lyra scenarios.
