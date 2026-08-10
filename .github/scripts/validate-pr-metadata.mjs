import { pathToFileURL } from "node:url";

const RELEASE_TYPES = new Map([
    ["feat", "minor"],
    ["fix", "patch"],
    ["perf", "patch"],
    ["revert", "patch"],
]);

const NO_RELEASE_TYPES = new Set([
    "build",
    "chore",
    "ci",
    "docs",
    "refactor",
    "style",
    "test",
]);

const ALLOWED_TYPES = new Set([...RELEASE_TYPES.keys(), ...NO_RELEASE_TYPES]);
const TITLE_PATTERN = /^(?<type>[a-z]+)(?:\((?<scope>[a-z0-9][a-z0-9._/-]*)\))?(?<breaking>!)?: (?<description>\S.*)$/;
const BREAKING_FOOTER_CANDIDATE = /^BREAKING(?:[ -]CHANGES?)?:/;
const VALID_BREAKING_FOOTER = /^BREAKING CHANGE:\s+\S/;

export function validatePullRequestMetadata(title, body = "") {
    const errors = [];
    const match = TITLE_PATTERN.exec(title);

    if (!match) {
        errors.push(
            "PR title must use Conventional Commit format: type(optional-scope)!: description",
        );
        return { errors, releaseType: null };
    }

    const { type, breaking } = match.groups;
    if (!ALLOWED_TYPES.has(type)) {
        errors.push(
            `Unsupported Conventional Commit type '${type}'. Allowed types: ${[...ALLOWED_TYPES].sort().join(", ")}`,
        );
    }

    const breakingFooterLines = body
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => BREAKING_FOOTER_CANDIDATE.test(line));
    const validBreakingFooterLines = breakingFooterLines.filter((line) =>
        VALID_BREAKING_FOOTER.test(line),
    );

    if (breakingFooterLines.length !== validBreakingFooterLines.length) {
        errors.push(
            "Breaking-change footers must use 'BREAKING CHANGE: description' with a non-empty description",
        );
    }

    if (breaking && validBreakingFooterLines.length === 0) {
        errors.push(
            "A PR title containing '!' must include a 'BREAKING CHANGE: description' footer in the PR body",
        );
    }

    const releaseType = breaking || validBreakingFooterLines.length > 0
        ? "major"
        : RELEASE_TYPES.get(type) ?? null;

    return { errors, releaseType };
}

function run() {
    const title = process.env.PR_TITLE ?? "";
    const body = process.env.PR_BODY ?? "";
    const { errors, releaseType } = validatePullRequestMetadata(title, body);

    if (errors.length > 0) {
        console.error("PR metadata validation failed:");
        for (const error of errors) {
            console.error(`- ${error}`);
        }
        process.exitCode = 1;
        return;
    }

    console.log("PR metadata is valid.");
    console.log(`Release impact: ${releaseType ?? "none"}`);
}

const entryPoint = process.argv[1]
    ? pathToFileURL(process.argv[1]).href
    : null;
if (entryPoint === import.meta.url) {
    run();
}
