import { createHash } from "node:crypto";
import { appendFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const DEFAULT_PLUGIN_XML_ID = "com.cmroche.unrealhelper";
const GITHUB_API_VERSION = "2022-11-28";
const STABLE_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const ALLOWED_HTML_TAGS = new Set([
    "a",
    "br",
    "code",
    "em",
    "h1",
    "h2",
    "h3",
    "hr",
    "li",
    "ol",
    "p",
    "pre",
    "strong",
    "ul",
]);

export function normalizeVersion(value, label = "version") {
    const normalized = String(value ?? "").trim().replace(/^v/, "");
    const match = STABLE_VERSION.exec(normalized);
    if (!match) {
        throw new Error(`${label} must be a stable semantic version such as 1.2.3; received '${value}'.`);
    }

    return normalized;
}

export function compareVersions(left, right) {
    const leftParts = normalizeVersion(left, "left version").split(".").map(Number);
    const rightParts = normalizeVersion(right, "right version").split(".").map(Number);

    for (let index = 0; index < leftParts.length; index += 1) {
        if (leftParts[index] !== rightParts[index]) {
            return Math.sign(leftParts[index] - rightParts[index]);
        }
    }

    return 0;
}

export function latestStableMarketplaceVersion(updates) {
    const stableUpdates = updates
        .filter((update) => update.channel === "" || update.channel === undefined || update.channel === null)
        .filter((update) => update.approve === true)
        .map((update) => ({
            uploadedAt: Number(update.cdate),
            version: normalizeVersion(update.version, "Marketplace version"),
        }))
        .filter((update) => Number.isFinite(update.uploadedAt));

    if (stableUpdates.length === 0) {
        throw new Error("JetBrains Marketplace returned no approved Stable updates with upload dates.");
    }

    stableUpdates.sort((left, right) => right.uploadedAt - left.uploadedAt);
    return stableUpdates[0].version;
}

function releaseVersion(release) {
    return normalizeVersion(release.tag_name, "GitHub Release tag");
}

export function selectReleaseRange(releases, previousVersion, targetVersion) {
    const previous = normalizeVersion(previousVersion, "previous Marketplace version");
    const target = normalizeVersion(targetVersion, "target GitHub version");
    const comparison = compareVersions(target, previous);

    if (comparison < 0) {
        throw new Error(`Target ${target} is older than Marketplace version ${previous}.`);
    }
    if (comparison === 0) {
        return [];
    }

    const publishedReleases = releases
        .filter((release) => !release.draft && !release.prerelease)
        .map((release) => ({ ...release, version: releaseVersion(release) }));

    if (!publishedReleases.some((release) => release.version === target)) {
        throw new Error(`GitHub Release v${target} does not exist or is not a published Stable release.`);
    }

    const selected = publishedReleases
        .filter((release) => compareVersions(release.version, previous) > 0)
        .filter((release) => compareVersions(release.version, target) <= 0)
        .sort((left, right) => compareVersions(left.version, right.version));

    if (selected.length === 0 || selected.at(-1).version !== target) {
        throw new Error(`No complete GitHub Release range exists from v${previous} through v${target}.`);
    }

    return selected;
}

function escapeAttribute(value) {
    return value
        .replaceAll("&", "&amp;")
        .replaceAll('"', "&quot;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;");
}

export function sanitizeGitHubHtml(html) {
    const withoutDangerousBlocks = String(html ?? "")
        .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, "");

    const sanitized = withoutDangerousBlocks.replace(/<[^>]*>/g, (tag) => {
        const closing = /^<\s*\/\s*([a-z0-9]+)\s*>$/i.exec(tag);
        if (closing) {
            const name = closing[1].toLowerCase();
            return ALLOWED_HTML_TAGS.has(name) && !["br", "hr"].includes(name) ? `</${name}>` : "";
        }

        const opening = /^<\s*([a-z0-9]+)\b([^>]*)>$/i.exec(tag);
        if (!opening) {
            return "";
        }

        const name = opening[1].toLowerCase();
        if (!ALLOWED_HTML_TAGS.has(name)) {
            return "";
        }
        if (name !== "a") {
            return `<${name}>`;
        }

        const hrefMatch = /\bhref\s*=\s*(?:"([^"]+)"|'([^']+)')/i.exec(opening[2]);
        const href = hrefMatch?.[1] ?? hrefMatch?.[2];
        if (!href) {
            return "";
        }

        const url = new URL(href);
        if (url.protocol !== "https:" || url.hostname !== "github.com") {
            throw new Error(`Release notes contain an unsupported link: ${href}`);
        }

        return `<a href="${escapeAttribute(url.toString())}">`;
    }).trim();

    if (!sanitized) {
        throw new Error("A selected GitHub Release has empty change notes.");
    }
    if (sanitized.includes("]]>")) {
        throw new Error("Release notes contain a CDATA terminator and cannot be packaged safely.");
    }

    return sanitized;
}

export function renderChangeNotes(releases) {
    if (releases.length === 0) {
        return "";
    }

    return `${releases.map((release) => sanitizeGitHubHtml(release.body_html)).join("\n\n")}\n`;
}

export function planMarketplaceRelease({
    plugin,
    updates,
    releases,
    requestedTargetVersion,
    previousVersionOverride,
}) {
    if (plugin.hasUnapprovedUpdate) {
        throw new Error("JetBrains Marketplace already has an update awaiting approval; wait for it to finish before submitting another.");
    }

    const publishedReleases = releases.filter((release) => !release.draft && !release.prerelease);
    if (publishedReleases.length === 0) {
        throw new Error("GitHub returned no published Stable releases.");
    }

    const latestReleaseVersion = publishedReleases
        .map(releaseVersion)
        .sort((left, right) => compareVersions(right, left))[0];
    const targetVersion = requestedTargetVersion
        ? normalizeVersion(requestedTargetVersion, "requested target version")
        : latestReleaseVersion;
    const marketplaceVersion = latestStableMarketplaceVersion(updates);
    const previousVersion = previousVersionOverride
        ? normalizeVersion(previousVersionOverride, "previous version override")
        : marketplaceVersion;
    const selectedReleases = selectReleaseRange(releases, previousVersion, targetVersion);
    const changeNotes = renderChangeNotes(selectedReleases);

    return {
        changeNotes,
        marketplaceVersion,
        previousVersion,
        releaseVersions: selectedReleases.map((release) => release.version),
        shouldPublish: selectedReleases.length > 0,
        targetVersion,
    };
}

async function fetchResponse(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        const details = (await response.text()).trim();
        throw new Error(`${url} returned HTTP ${response.status}${details ? `: ${details}` : "."}`);
    }
    return response;
}

async function fetchGitHubReleases(repository, token) {
    const releases = [];
    const headers = {
        Accept: "application/vnd.github.html+json",
        "X-GitHub-Api-Version": GITHUB_API_VERSION,
    };
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }

    for (let page = 1; page <= 10; page += 1) {
        const url = `https://api.github.com/repos/${repository}/releases?per_page=100&page=${page}`;
        const pageReleases = await (await fetchResponse(url, { headers })).json();
        releases.push(...pageReleases);
        if (pageReleases.length < 100) {
            return releases;
        }
    }

    throw new Error("GitHub returned more than 1,000 releases; narrow the release lookup before publishing.");
}

async function prepareFromEnvironment(environment = process.env) {
    const repository = requiredEnvironment(environment, "GITHUB_REPOSITORY");
    if (!/^[^/]+\/[^/]+$/.test(repository)) {
        throw new Error(`GITHUB_REPOSITORY must use owner/name format; received '${repository}'.`);
    }

    const pluginXmlId = environment.MARKETPLACE_PLUGIN_XML_ID || DEFAULT_PLUGIN_XML_ID;
    const pluginUrl = `https://plugins.jetbrains.com/api/plugins/intellij/${encodeURIComponent(pluginXmlId)}`;
    const plugin = await (await fetchResponse(pluginUrl)).json();
    const updatesUrl = `https://plugins.jetbrains.com/api/plugins/${plugin.id}/updates?channel=&size=100&offset=0`;
    const [updates, releases] = await Promise.all([
        (await fetchResponse(updatesUrl)).json(),
        fetchGitHubReleases(repository, environment.GITHUB_TOKEN),
    ]);

    const plan = planMarketplaceRelease({
        plugin,
        updates,
        releases,
        requestedTargetVersion: environment.TARGET_VERSION,
        previousVersionOverride: environment.PREVIOUS_VERSION,
    });
    const expectedMarketplaceVersion = environment.EXPECTED_MARKETPLACE_VERSION?.trim();
    if (expectedMarketplaceVersion && plan.marketplaceVersion !== normalizeVersion(expectedMarketplaceVersion)) {
        throw new Error(
            `Marketplace changed during this run: expected ${expectedMarketplaceVersion}, found ${plan.marketplaceVersion}.`,
        );
    }

    const changeNotesFile = requiredEnvironment(environment, "CHANGE_NOTES_FILE");
    await writeFile(changeNotesFile, plan.changeNotes, "utf8");

    const notesSha256 = createHash("sha256").update(plan.changeNotes).digest("hex");
    if (environment.GITHUB_OUTPUT) {
        await appendFile(
            environment.GITHUB_OUTPUT,
            [
                `notes_sha256=${notesSha256}`,
                `marketplace_version=${plan.marketplaceVersion}`,
                `previous_version=${plan.previousVersion}`,
                `release_versions=${plan.releaseVersions.join(", ")}`,
                `should_publish=${plan.shouldPublish}`,
                `target_version=${plan.targetVersion}`,
                "",
            ].join("\n"),
        );
    }

    if (environment.GITHUB_STEP_SUMMARY) {
        const status = plan.shouldPublish ? "Ready to submit" : "Already uploaded";
        const notes = plan.changeNotes || "<p>No Marketplace update is required.</p>";
        await appendFile(
            environment.GITHUB_STEP_SUMMARY,
            [
                "## JetBrains Marketplace update",
                "",
                `| Status | Marketplace | Target | Included releases |`,
                `| --- | --- | --- | --- |`,
                `| ${status} | ${plan.previousVersion} | ${plan.targetVersion} | ${plan.releaseVersions.join(", ") || "None"} |`,
                "",
                "<details><summary>Packaged change notes</summary>",
                "",
                notes,
                "",
                "</details>",
                "",
            ].join("\n"),
        );
    }

    return plan;
}

function requiredEnvironment(environment, name) {
    const value = environment[name]?.trim();
    if (!value) {
        throw new Error(`${name} is required.`);
    }
    return value;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    prepareFromEnvironment().catch((error) => {
        console.error(`Marketplace release preparation failed: ${error.message}`);
        process.exitCode = 1;
    });
}
