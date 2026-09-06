import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { configuredDemoMerchant } from "./demo-access.ts";

const sessionCss = readFileSync(new URL("../../components/merchant/merchant-session.module.css", import.meta.url), "utf8");
const sessionSource = readFileSync(new URL("../../components/merchant/merchant-session.tsx", import.meta.url), "utf8");
const shellCss = readFileSync(new URL("../../components/merchant/merchant-shell.module.css", import.meta.url), "utf8");

test("Merchant demo access requires both public values and returns form-fill data only", () => {
  const configuredPassword = "x".repeat(16);
  assert.equal(configuredDemoMerchant(undefined, undefined), null);
  assert.equal(configuredDemoMerchant("demo-amazing-admin@agentic-commerce.invalid", undefined), null);
  assert.equal(configuredDemoMerchant(undefined, configuredPassword), null);
  assert.deepEqual(
    configuredDemoMerchant(" demo-amazing-admin@agentic-commerce.invalid ", configuredPassword),
    {
      identity: "demo-amazing-admin@agentic-commerce.invalid",
      password: configuredPassword,
    },
  );
});

test("Merchant demo control reads public config and fills without authenticating", () => {
  assert.match(sessionSource, /process\.env\.NEXT_PUBLIC_DEMO_MERCHANT_IDENTITY/);
  assert.match(sessionSource, /process\.env\.NEXT_PUBLIC_DEMO_MERCHANT_PASSWORD/);
  assert.match(sessionSource, /type="button"/);
  assert.match(sessionSource, /setIdentity\(demoMerchant\.identity\);\s*setPassword\(demoMerchant\.password\);/);
  assert.match(sessionSource, />Use Amazing demo account<\/AmanaButton>/);
  const demoHandler = sessionSource.match(/onClick=\{\(\) => \{([\s\S]*?)\}\}\s*type="button"/)?.[1] ?? "";
  assert.doesNotMatch(demoHandler, /signIn|merchantApi|fetch|localStorage|sessionStorage|document\.cookie/);
});

test("narrow demo access stacks, wraps its identity, and retains comfortable mobile padding", () => {
  assert.match(sessionCss, /@media \(max-width: 64rem\)[\s\S]*?\.demoAccess\s*{[\s\S]*?grid-template-columns:\s*1fr/);
  assert.match(sessionCss, /\.demoAccess strong\s*{[\s\S]*?overflow-wrap:\s*anywhere/);
  assert.match(sessionCss, /@media \(max-width: 30rem\)[\s\S]*?\.authPage\s*{[\s\S]*?padding:\s*0\.75rem/);
});

test("mobile navigation is a left drawer with one authored close control and a footer", () => {
  assert.match(shellCss, /\.mobileDrawer\s*{[\s\S]*?inset:\s*0 auto 0 0/);
  assert.match(shellCss, /@keyframes drawer-in[\s\S]*?translateX\(-100%\)/);
  assert.match(shellCss, /\.drawerFooter\s*{/);
});
