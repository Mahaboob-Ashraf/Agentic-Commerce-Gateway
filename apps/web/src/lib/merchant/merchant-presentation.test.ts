import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const sessionCss = readFileSync(new URL("../../components/merchant/merchant-session.module.css", import.meta.url), "utf8");
const shellCss = readFileSync(new URL("../../components/merchant/merchant-shell.module.css", import.meta.url), "utf8");

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
