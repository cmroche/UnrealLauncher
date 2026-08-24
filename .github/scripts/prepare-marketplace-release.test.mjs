import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    compareVersions,
    latestStableMarketplaceVersion,
    normalizeVersion,
    planMarketplaceRelease,
    renderChangeNotes,
    sanitizeGitHubHtml,
    selectReleaseRange,
} from "./prepare-marketplace-release.mjs";

function release(version, bodyHtml = `<h2>${version}</h2><ul><li>Change ${version}</li></ul>`) {
    return {
        body_html: bodyHtml,
        draft: false,
        prerelease: false,
        tag_name: `v${version}`,
    };
}

const updates = [
    { approve: true, cdate: "200", channel: "", version: "1.2.3" },
    { approve: true, cdate: "100", channel: "", version: "1.2.1" },
];
const releases = [release("1.2.5"), release("1.2.4"), release("1.2.3"), release("1.2.2")];

test("stable versions normalize and compare numerically", () => {
    assert.equal(normalizeVersion("v1.2.3"), "1.2.3");
    assert.equal(compareVersions("1.10.0", "1.9.9"), 1);
    assert.equal(compareVersions("1.2.3", "1.2.3"), 0);
    assert.throws(() => normalizeVersion("1.2.3-beta.1"), /stable semantic version/);
});

test("Marketplace baseline follows the latest Stable upload date", () => {
    assert.equal(
        latestStableMarketplaceVersion([
            { approve: true, cdate: "300", channel: "", version: "1.9.0" },
            { approve: true, cdate: "200", channel: "", version: "2.0.0" },
            { approve: true, cdate: "400", channel: "beta", version: "3.0.0" },
        ]),
        "1.9.0",
    );
});

test("release range includes every skipped GitHub release in ascending order", () => {
    assert.deepEqual(
        selectReleaseRange(releases, "1.2.3", "1.2.5").map((item) => item.version),
        ["1.2.4", "1.2.5"],
    );
});

test("equal target is a successful no-op and older target fails", () => {
    assert.deepEqual(selectReleaseRange(releases, "1.2.3", "1.2.3"), []);
    assert.throws(() => selectReleaseRange(releases, "1.2.5", "1.2.4"), /older than Marketplace/);
});

test("requested target must be a published Stable GitHub Release", () => {
    assert.throws(() => selectReleaseRange(releases, "1.2.3", "1.2.6"), /does not exist/);
    assert.throws(
        () => selectReleaseRange([...releases, { ...release("1.2.6"), draft: true }], "1.2.3", "1.2.6"),
        /does not exist/,
    );
});

test("GitHub HTML is reduced to Marketplace-safe tags and links", () => {
    const html = sanitizeGitHubHtml(
        '<h1>Major release</h1><h2 class="heading">Release</h2><script>alert(1)</script><p><a href="https://github.com/cmroche/UnrealLauncher" data-id="1">Notes</a></p>',
    );
    assert.equal(
        html,
        '<h1>Major release</h1><h2>Release</h2><p><a href="https://github.com/cmroche/UnrealLauncher">Notes</a></p>',
    );
    assert.throws(
        () => sanitizeGitHubHtml('<a href="https://example.com">Unexpected</a>'),
        /unsupported link/,
    );
});

test("change notes combine selected releases in order", () => {
    assert.equal(
        renderChangeNotes([release("1.2.4"), release("1.2.5")]),
        "<h2>1.2.4</h2><ul><li>Change 1.2.4</li></ul>\n\n<h2>1.2.5</h2><ul><li>Change 1.2.5</li></ul>\n",
    );
});

test("release plan uses live Marketplace state and refuses a pending update", () => {
    const plan = planMarketplaceRelease({
        plugin: { hasUnapprovedUpdate: false },
        releases,
        requestedTargetVersion: "1.2.5",
        updates,
    });
    assert.equal(plan.previousVersion, "1.2.3");
    assert.equal(plan.marketplaceVersion, "1.2.3");
    assert.equal(plan.targetVersion, "1.2.5");
    assert.deepEqual(plan.releaseVersions, ["1.2.4", "1.2.5"]);
    assert.equal(plan.shouldPublish, true);

    assert.throws(
        () => planMarketplaceRelease({
            plugin: { hasUnapprovedUpdate: true },
            releases,
            requestedTargetVersion: "1.2.5",
            updates,
        }),
        /awaiting approval/,
    );
});

test("previous version override handles hidden or otherwise unlisted uploads", () => {
    const plan = planMarketplaceRelease({
        plugin: { hasUnapprovedUpdate: false },
        previousVersionOverride: "1.2.4",
        releases,
        requestedTargetVersion: "1.2.5",
        updates,
    });
    assert.equal(plan.marketplaceVersion, "1.2.3");
    assert.equal(plan.previousVersion, "1.2.4");
    assert.deepEqual(plan.releaseVersions, ["1.2.5"]);
});

test("blank target selects the highest published Stable version", () => {
    const plan = planMarketplaceRelease({
        plugin: { hasUnapprovedUpdate: false },
        releases: [release("1.2.4"), release("1.2.3"), release("1.2.5")],
        updates,
    });
    assert.equal(plan.targetVersion, "1.2.5");
});

test("manual workflow builds the exact tag and publishes only after verification", async () => {
    const workflow = await readFile(
        new URL("../workflows/publish-marketplace.yml", import.meta.url),
        "utf8",
    );

    assert.match(workflow, /^\s{2}workflow_dispatch:/m);
    assert.match(workflow, /git checkout --detach "v\$TARGET_VERSION"/);
    assert.match(workflow, /clean test verifyPlugin verifyReleaseArtifact/);
    assert.match(workflow, /EXPECTED_MARKETPLACE_VERSION/);
    assert.match(workflow, /publishPlugin/);
    assert.match(workflow, /PUBLISH_TOKEN: \$\{\{ secrets\.PUBLISH_TOKEN \}\}/);
});
