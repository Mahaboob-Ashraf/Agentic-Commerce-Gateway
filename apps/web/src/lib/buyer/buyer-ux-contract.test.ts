import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const shell = readFileSync(new URL("../../components/buyer/buyer-shell.tsx", import.meta.url), "utf8");
const session = readFileSync(new URL("../../components/buyer/buyer-session.tsx", import.meta.url), "utf8");
const onboarding = readFileSync(new URL("../../components/buyer/onboarding.tsx", import.meta.url), "utf8");
const markComponent = readFileSync(new URL("../../components/buyer/amana-mark.tsx", import.meta.url), "utf8");
const markAsset = readFileSync(new URL("../../../public/amana/amana-mark.png", import.meta.url));

test("Buyer brand surfaces use the canonical Amana PNG mark", () => {
  assert.match(markComponent, /src="\/amana\/amana-mark\.png"/);
  assert.equal(markAsset.subarray(1, 4).toString("ascii"), "PNG");
  assert.match(shell, /<AmanaMark/);
  assert.match(session, /<AmanaMark/);
  assert.match(onboarding, /<AmanaMark/);
});

test("New chat uses canonical routing and remounts only the conversation workspace", () => {
  assert.match(shell, /href="\/buyer\/chat" onClick=\{beginNewChat\}/);
  assert.match(shell, /setWorkspaceGeneration\(\(current\) => nextNewChatGeneration\(current\)\)/);
  assert.match(shell, /<main className=\{styles\.workspace\} key=\{buyerWorkspaceKey\(workspaceGeneration\)\}>/);
  assert.match(shell, /const \[threads, setThreads\] = useState<CommerceThread\[\]>/);
  assert.doesNotMatch(shell, /location\.reload|deleteThread/);
});

test("demo access fills the normal login form without authenticating", () => {
  assert.match(session, /<form className=\{styles\.authForm\} onSubmit=\{submit\}>/);
  assert.equal(session.match(/await signIn\(identity, password\)/g)?.length, 1);
  assert.match(session, /setIdentity\(demoBuyer\.identity\); setPassword\(demoBuyer\.password\)/);
  assert.match(session, /Fills the form only\. Continue with the normal secure sign-in\./);
});

test("wrong-role Buyer access exposes the authenticated sign-out path", () => {
  assert.match(session, /actor\.role !== "BUYER"/);
  assert.match(session, /await signOut\(\)/);
  assert.match(session, /onClick=\{\(\) => void leaveWrongRole\(\)\}/);
  assert.match(session, /"Sign out"/);
});

test("onboarding writes only the per-actor device marker after backend readiness", () => {
  assert.match(onboarding, /if \(!actor \|\| !canEnterBuyer\(status\)\) return/);
  assert.match(onboarding, /localStorage\.setItem\(buyerOnboardingSeenKey\(actor\.actorId\), "true"\)/);
  assert.match(onboarding, /buyerApi\.saveProfile|buyerApi\.createAddress|buyerApi\.selectAddress/);
});
