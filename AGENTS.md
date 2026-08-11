# UnrealHelper contributor guide

## Project overview

UnrealHelper is a Kotlin-based JetBrains Rider plugin for Unreal Engine projects. It provides toolbar actions for global launch arguments, target/platform configurations, Unreal Build/Cook/Package commands, and quick launching cooked executables.

The project targets Java 21 and Rider `2026.1.4`.

## Repository layout

- `src/main/kotlin/com/cmroche/unrealhelper/`: production plugin code, organized by feature (`actions`, `command`, `config`, `discovery`, `launch`, `run`, `settings`, `terminal`, and `ui`).
- `src/main/resources/META-INF/plugin.xml`: plugin registration and extension declarations.
- `src/test/kotlin/com/cmroche/unrealhelper/`: JUnit 4 tests mirroring the production package layout.
- `docs/design.md`: product behavior and data-ownership decisions.
- `docs/manual-validation.md`: Rider/Unreal end-to-end checks.

## Development workflow

Use the Gradle wrapper; do not depend on a system Gradle installation.

```bash
./gradlew test
./gradlew build
./gradlew runIde
./gradlew verifyPlugin
```

`runIde` opens a Rider sandbox and, by default, opens the Lyra project at `~/Projects/UnrealEngine/Samples/Games/Lyra/Lyra.uproject`. Override it when needed:

```bash
./gradlew runIde -PunrealHelper.runProject=/absolute/path/to/Project.uproject
```

Run focused tests while iterating:

```bash
./gradlew test --tests 'com.cmroche.unrealhelper.command.UnrealCommandBuilderTest'
```

## Versioning and commit conventions

- Releases follow Semantic Versioning (`MAJOR.MINOR.PATCH`) and are derived from Conventional Commit messages on `main`.
- Pull requests must be squash merged. The PR title becomes the squash commit subject and must use the form `type(optional-scope): description`, such as `fix: reject editor targets during cook` or `feat(config): add shared launch presets`.
- Intermediate commits within a pull request do not need Conventional Commit messages because squash merging replaces them with one commit on `main`. Verify the final squash commit message before merging.
- Use `feat:` for a minor release; use `fix:`, `perf:`, or `revert:` for a patch release.
- Use `type!:` or a `BREAKING CHANGE: description` footer for a major release. A title containing `!` must include that exact footer in the PR body so it becomes part of the squash commit body.
- `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, and `test:` changes do not create a release. Direct commits to `main` must follow the same Conventional Commit format.
- Do not manually change the plugin version for routine work; release automation owns release versions unless a task explicitly concerns release bootstrapping or versioning infrastructure.
- PR title/body validation and plugin build checks are advisory until required branch-protection checks are available for this private repository. Confirm they pass before merging.

## Implementation conventions

- Keep domain and command-building logic pure where possible. Use injected inputs such as paths, platform names, and OS names rather than directly reading IDE state; this keeps behavior unit-testable.
- Use JetBrains platform APIs at the integration boundary only. Keep UI actions thin and delegate behavior to feature-specific services or builders.
- Preserve command arguments as `List<String>` until terminal/process rendering. Quote only at the shell boundary.
- Target/platform configuration data shared by a project belongs in `.unrealhelper/target-platforms.json`; IDE-local selections and settings belong in the project settings store.
- Treat duplicate configuration entries as meaningful: build/cook/package may deduplicate equivalent command contexts, while quick launch must retain repeated entries and start one process for each.
- Validate action inputs before starting external Unreal commands. Errors should identify the selected configuration or entry causing the problem.
- Follow existing Kotlin formatting: four-space indentation, trailing commas in multiline declarations and calls, and descriptive backtick-quoted test names.

## Testing and validation

- Add or update a JUnit test in the matching test package for behavior changes.
- Run the narrowest relevant test class first, then `./gradlew test` before handoff when practical.
- For UI, terminal, process-launch, persistence, or Rider integration changes, also follow the applicable checks in `docs/manual-validation.md` using `runIde`.
- If a change affects expected UX or data ownership, update `docs/design.md` and/or `docs/manual-validation.md` in the same change.

## Change safety

- Do not edit generated Gradle, IDE sandbox, build-output, or Unreal package files.
- Preserve unrelated working-tree changes.
- Prefer a small, cohesive change set; avoid unrelated refactors while implementing a feature or fix.
