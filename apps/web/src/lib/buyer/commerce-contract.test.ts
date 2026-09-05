import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { CommerceResponseContractError, parseCommerceRequestResult } from "./commerce-contract.ts";

const valid = {
  requestId: "request-1",
  threadId: "thread-1",
  state: "SEARCHING",
  requestStatus: "RUNNING",
  clarificationRequired: false,
  hardRequirements: [],
  softPreferences: [],
  products: [],
  constraints: [],
  riskReasonCodes: [],
  progress: [],
  evidenceReferences: [],
  explicitAuthorizationRequired: false,
  paymentReady: false,
  authorizationState: "NOT_REQUIRED",
  nextAction: "WAIT",
};

test("a valid commerce response preserves its durable identity", () => {
  const parsed = parseCommerceRequestResult(valid);
  assert.equal(parsed.threadId, "thread-1");
  assert.equal(parsed.requestId, "request-1");
  assert.equal(parsed.merchantLogoUrl, null);
});

test("merchant presentation accepts safe metadata and rejects executable or protocol-relative URLs", () => {
  assert.equal(parseCommerceRequestResult({ ...valid, merchantLogoUrl: "/amana/merchant/amazing.png" }).merchantLogoUrl, "/amana/merchant/amazing.png");
  assert.throws(() => parseCommerceRequestResult({ ...valid, merchantLogoUrl: "javascript:alert(1)" }), CommerceResponseContractError);
  assert.throws(() => parseCommerceRequestResult({ ...valid, merchantLogoUrl: "//untrusted.example/logo.png" }), CommerceResponseContractError);
});

test("an empty successful response fails closed before UI state is mutated", () => {
  assert.throws(() => parseCommerceRequestResult(undefined), CommerceResponseContractError);
});

test("a response without a durable thread identity fails closed", () => {
  assert.throws(
    () => parseCommerceRequestResult({ ...valid, threadId: undefined }),
    /invalid commerce response/i,
  );
});

test("Buyer requests use the same transparent rewrite proxy as session authentication", () => {
  const nextConfig = readFileSync(new URL("../../../next.config.ts", import.meta.url), "utf8");
  assert.match(nextConfig, /source:\s*"\/api\/auth\/:path\*"/);
  assert.match(nextConfig, /source:\s*"\/api\/buyer\/:path\*"/);
  assert.match(nextConfig, /preserves[\s\S]*session cookie, CSRF header, and multipart boundary/);
});

test("running durable requests attach to status recovery instead of surfacing the raw conflict", () => {
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  const api = readFileSync(new URL("./api.ts", import.meta.url), "utf8");
  assert.match(chat, /COMMERCE_REQUEST_RUNNING/);
  assert.match(chat, /recoverCommerceRequest/);
  assert.match(chat, /Reattached to its durable progress/);
  assert.match(api, /commerceRequest\(requestId/);
});

test("Sending clears only after the durable request becomes observable", () => {
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  assert.match(chat, /observeCommerceAcceptance/);
  assert.match(chat, /buyerApi\.commerceRequest\(requestId\)/);
  assert.match(chat, /COMMERCE_REQUEST_ACCEPTING/);
  assert.doesNotMatch(chat, /onTransportAccepted/);
});

test("a denied mutation revalidates the Buyer session and retries with fresh CSRF once", () => {
  const api = readFileSync(new URL("./api.ts", import.meta.url), "utf8");
  assert.match(api, /response\.status === 403 && mutating && attempt === 0/);
  assert.match(api, /fetch\("\/api\/auth\/me"/);
  assert.match(api, /requestPath/);
  assert.match(api, /requestMethod/);
  assert.match(api, /\[buyer-api\] denied method=\$\{method\} path=\$\{path\}/);
});

test("Auralink image is authoritative catalogue data and the renderer has no SKU branch", () => {
  const catalogue = JSON.parse(readFileSync(
    new URL("../../../../../evaluation/demo-data/amazing-catalogue-v1.json", import.meta.url),
    "utf8",
  )) as { products: Array<{ merchantSku: string; facts?: Array<{ type: string; value: string }> }> };
  const product = catalogue.products.find((candidate) => candidate.merchantSku === "AMZ-AUDIO-032");
  assert.equal(product?.facts?.find((fact) => fact.type === "IMAGE")?.value, "/demo/products/auralink-buds.svg");
  const image = readFileSync(new URL("../../../public/demo/products/auralink-buds.svg", import.meta.url), "utf8");
  assert.match(image, /Auralink Buds Bluetooth Earphones/);
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  assert.match(chat, /authoritativeImage\(product\.facts\)/);
  assert.doesNotMatch(chat, /AMZ-AUDIO-032/);
});

test("merchant logo metadata renders across Buyer commerce identity surfaces without a name branch", () => {
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  const api = readFileSync(new URL("./api.ts", import.meta.url), "utf8");
  const logo = readFileSync(new URL("../../../public/amana/merchant/amazing.png", import.meta.url));
  assert.equal(logo.subarray(1, 4).toString("ascii"), "PNG");
  assert.match(api, /logoUrl: "\/amana\/merchant\/amazing\.png"/);
  assert.match(chat, /logoUrl=\{result\.merchantLogoUrl\}/);
  assert.match(chat, /merchantIdentityLarge/);
  assert.match(chat, /merchantLogoUrl=\{result\.merchantLogoUrl\}/);
  assert.match(chat, /image: result\.merchantLogoUrl \?\? undefined/);
  assert.doesNotMatch(chat, /merchantDisplayName\s*===\s*["']Amazing["']/);
});
