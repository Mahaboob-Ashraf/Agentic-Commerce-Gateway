import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";

function run(name, script) {
  const result = spawnSync(pnpm, ["--filter", "@agentic-commerce/web", script], {
    cwd: root, encoding: "utf8", shell: process.platform === "win32"
  });
  process.stdout.write(result.stdout || ""); process.stderr.write(result.stderr || "");
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${name} frontend tests failed with exit code ${result.status}`);
  const output = `${result.stdout || ""}\n${result.stderr || ""}`;
  const number = label => {
    const matches = [...output.matchAll(new RegExp(`^(?:#|ℹ) ${label} (\\d+)$`, "gm"))];
    return matches.length ? Number(matches.at(-1)[1]) : null;
  };
  return { name, tests: number("tests"), passed: number("pass"), failed: number("fail"), skipped: number("skipped"), command: `pnpm --filter @agentic-commerce/web ${script}` };
}

function commandOutput(command, args) { try { return execFileSync(command, args, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch { return "UNAVAILABLE"; } }

try {
  const suites = [run("Buyer", "test:commerce"), run("Merchant", "test:merchant"), run("Proof", "test:proof"), run("Security", "test:security")];
  if (suites.some(value => value.tests === null || value.passed === null || value.failed === null)) throw new Error("Could not parse Node test-runner counts");
  const totals = suites.reduce((sum, value) => ({ tests: sum.tests + value.tests, passed: sum.passed + value.passed, failed: sum.failed + value.failed, skipped: sum.skipped + (value.skipped || 0) }), { tests: 0, passed: 0, failed: 0, skipped: 0 });
  const artifact = { schemaVersion: "amana-frontend-test-evidence-v1", provenance: {
    commitSha: commandOutput("git", ["rev-parse", "HEAD"]), workingTreeDirty: commandOutput("git", ["status", "--porcelain"]) !== "",
    generatedAtUtc: new Date().toISOString(), runtime: { node: process.version }, operatingSystem: `${os.type()} ${os.release()} ${os.arch()}`,
    hostLabel: process.env.RUNNER_NAME || os.hostname(), sampleSize: totals.tests, command: "pnpm web:test"
  }, summary: { status: totals.failed === 0 ? "PASS" : "FAIL", ...totals }, suites,
  limitations: ["Counts are the Node test runner output for Buyer, Merchant, and Proof-page suites and remain separate from backend/proof counts."],
  whatThisDoesNotProve: "Frontend unit/contract tests do not prove browser compatibility, accessibility, backend safety, or deployment behavior." };
  fs.mkdirSync(path.join(root, "proof", "results"), { recursive: true });
  fs.writeFileSync(path.join(root, "proof", "results", "frontend-tests.json"), `${JSON.stringify(artifact, null, 2)}\n`);
} catch (error) { console.error(error.message); process.exitCode = 1; }
