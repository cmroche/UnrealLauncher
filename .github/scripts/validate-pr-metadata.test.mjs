import assert from "node:assert/strict";
import test from "node:test";

import { validatePullRequestMetadata } from "./validate-pr-metadata.mjs";

test("accepts feature titles as minor releases", () => {
    const result = validatePullRequestMetadata(
        "feat(config): add shared launch presets",
        "Adds reusable launch presets.",
    );

    assert.deepEqual(result, { errors: [], releaseType: "minor" });
});

test("accepts fix titles as patch releases", () => {
    const result = validatePullRequestMetadata(
        "fix: reject editor targets during cook",
        "Rejects invalid target metadata before invoking UAT.",
    );

    assert.deepEqual(result, { errors: [], releaseType: "patch" });
});

test("accepts documentation titles without creating a release", () => {
    const result = validatePullRequestMetadata(
        "docs: document plugin installation",
        "Explains how to install a release bundle.",
    );

    assert.deepEqual(result, { errors: [], releaseType: null });
});

test("accepts breaking title with a breaking-change footer", () => {
    const result = validatePullRequestMetadata(
        "feat(config)!: replace the configuration schema",
        "Changes the shared schema.\n\nBREAKING CHANGE: Existing version 2 files must be migrated.",
    );

    assert.deepEqual(result, { errors: [], releaseType: "major" });
});

test("treats a breaking-change footer as a major release without a title marker", () => {
    const result = validatePullRequestMetadata(
        "fix(config): migrate shared configuration files",
        "BREAKING CHANGE: Existing version 2 files must be migrated.",
    );

    assert.deepEqual(result, { errors: [], releaseType: "major" });
});

test("rejects a breaking title without a breaking-change footer", () => {
    const result = validatePullRequestMetadata(
        "feat!: replace the configuration schema",
        "Changes the shared schema.",
    );

    assert.deepEqual(result, {
        errors: [
            "A PR title containing '!' must include a 'BREAKING CHANGE: description' footer in the PR body",
        ],
        releaseType: "major",
    });
});

test("rejects malformed breaking-change footers", () => {
    const result = validatePullRequestMetadata(
        "feat!: replace the configuration schema",
        "BREAKING CHANGES:",
    );

    assert.deepEqual(result, {
        errors: [
            "Breaking-change footers must use 'BREAKING CHANGE: description' with a non-empty description",
            "A PR title containing '!' must include a 'BREAKING CHANGE: description' footer in the PR body",
        ],
        releaseType: "major",
    });
});

test("rejects unsupported commit types", () => {
    const result = validatePullRequestMetadata(
        "update: refresh dependencies",
        "Updates dependencies.",
    );

    assert.equal(result.releaseType, null);
    assert.equal(result.errors.length, 1);
    assert.match(result.errors[0], /Unsupported Conventional Commit type 'update'/);
});

test("rejects non-conventional and multiline titles", () => {
    for (const title of ["Update plugin", "WIP: add presets", "fix: valid\nchore: injected"]) {
        const result = validatePullRequestMetadata(title, "Description");
        assert.equal(result.releaseType, null);
        assert.equal(result.errors.length, 1);
        assert.match(result.errors[0], /Conventional Commit format/);
    }
});
