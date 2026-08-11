import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { analyzeCommits } from "@semantic-release/commit-analyzer";

const releaseConfig = JSON.parse(await readFile(new URL("../../.releaserc", import.meta.url)));
const [, analyzerOptions] = releaseConfig.plugins.find(
    ([plugin]) => plugin === "@semantic-release/commit-analyzer",
);
const silentLogger = {
    log() {},
};

async function analyze(message) {
    return analyzeCommits(
        analyzerOptions,
        {
            commits: [{ message }],
            logger: silentLogger,
        },
    );
}

test("semantic-release targets main with v-prefixed tags", () => {
    assert.deepEqual(releaseConfig.branches, ["main"]);
    assert.equal(releaseConfig.tagFormat, "v${version}");
});

test("feature commits create minor releases", async () => {
    assert.equal(await analyze("feat(config): add shared launch presets"), "minor");
});

test("fix, performance, and revert commits create patch releases", async () => {
    for (const message of [
        "fix: reject editor targets during cook",
        "perf: reduce target discovery allocations",
        "revert: restore launch behavior",
    ]) {
        assert.equal(await analyze(message), "patch", message);
    }
});

test("breaking commits create major releases", async () => {
    assert.equal(
        await analyze(
            "feat(config)!: replace the configuration schema\n\nBREAKING CHANGE: Existing files must be migrated.",
        ),
        "major",
    );
});

test("non-release commit types do not create releases", async () => {
    for (const type of [
        "build",
        "chore",
        "ci",
        "docs",
        "refactor",
        "style",
        "test",
    ]) {
        assert.equal(await analyze(`${type}: update repository metadata`), null, type);
    }
});

test("release preparation builds and verifies the versioned plugin asset", () => {
    const [, execOptions] = releaseConfig.plugins.find(
        ([plugin]) => plugin === "@semantic-release/exec",
    );
    assert.match(execOptions.prepareCmd, /-PpluginVersion=\$\{nextRelease\.version\}/);
    assert.match(execOptions.prepareCmd, /\btest\b/);
    assert.match(execOptions.prepareCmd, /\bbuildPlugin\b/);
    assert.match(execOptions.prepareCmd, /verifyReleaseArtifact/);

    const [, githubOptions] = releaseConfig.plugins.find(
        ([plugin]) => plugin === "@semantic-release/github",
    );
    assert.equal(
        githubOptions.assets[0].path,
        "build/distributions/UnrealLauncher-v*.zip",
    );
});
