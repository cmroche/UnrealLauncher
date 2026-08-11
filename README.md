# Unreal Launcher (for Rider)

Rider plugin for coordinating Unreal Engine build, cook, package, and direct-launch workflows from a shared Target & Platform configuration.

## Installation

Download `UnrealLauncher-vX.Y.Z.zip` from the latest [GitHub Release](https://github.com/cmroche/UnrealHelper/releases). In Rider, open **Settings | Plugins**, choose **Install Plugin from Disk** from the gear menu, select the ZIP without extracting it, and restart Rider when prompted.

## Using Unreal Launcher

/coming soon/

## Contributing

### SemVer and Conventional Commit

Use `type(optional-scope): description` for the title. Release impact is:

- `feat:` creates a minor release.
- `fix:`, `perf:`, and `revert:` create a patch release.
- `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, and `test:` create no release.
- A title using `!`, or a body footer written exactly as `BREAKING CHANGE: description`, creates a major release. Titles using `!` must include that footer in the PR body.

PR checks validate the title and body and run the plugin tests and build. These checks are advisory until required branch-protection checks are available for this private repository, so confirm they pass before squash merging.

### Building

Run `gradlew build` from the plugin root directory to build the plugin. The plugin build runs these tests automatically.

### Testing

Run the plugin tests with `gradlew test` from the plugin root directory. The plugin build runs these tests automatically.

### Debugging

Run `gradlew runIde` from the plugin root directory to launch Rider with the plugin installed. The plugin build runs this automatically.

### AI Policy

Use of AI is permitted, but all generated code must be self-reviewed for understanding, correctness, and verbosity before submitting a pull request.
