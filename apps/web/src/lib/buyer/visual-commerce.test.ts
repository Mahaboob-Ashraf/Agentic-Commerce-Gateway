import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
const api = readFileSync(new URL("./api.ts", import.meta.url), "utf8");
const nextConfig = readFileSync(new URL("../../../next.config.ts", import.meta.url), "utf8");
const catalogue = readFileSync(new URL("../../../../../evaluation/demo-data/amazing-catalogue-v1.json", import.meta.url), "utf8");
const auralinkAsset = readFileSync(new URL("../../../public/demo/products/auralink-buds.svg", import.meta.url), "utf8");
const strideAsset = readFileSync(new URL("../../../public/demo/products/stride-white-low-top.svg", import.meta.url), "utf8");

test("the Buyer composer previews and submits one visual request through the durable commerce API", () => {
  assert.match(chat, /type="file"/);
  assert.match(chat, /BUYER_IMAGE_ACCEPT/);
  assert.match(chat, /Selected product reference/);
  assert.match(chat, /createVisualCommerceRequest/);
  assert.match(api, /\/api\/buyer\/commerce-requests\/visual/);
  assert.match(api, /init\.body instanceof FormData/);
  assert.match(nextConfig, /source:\s*"\/api\/buyer\/:path\*"/);
  assert.match(nextConfig, /destination:\s*`\$\{backendOrigin\}\/api\/buyer\/:path\*`/);
});

test("visual results disclose hypothesis authority and exact-versus-similar identity", () => {
  assert.match(chat, /Visual hypothesis · not catalogue truth/);
  assert.match(chat, /Exact grounded match/);
  assert.match(chat, /Visually similar grounded result/);
  assert.match(chat, /Merchant evidence below determines the actual product/);
});

test("provider capacity has bounded copy distinct from a safety failure", () => {
  assert.match(chat, /AI_PROVIDER_RATE_LIMITED/);
  assert.match(chat, /reasoning service is temporarily rate-limited/);
  assert.match(chat, /Nothing was authorized/);
});

test("Auralink and Stride image paths originate in catalogue facts and resolve to real static assets", () => {
  assert.match(catalogue, /"merchantSku":"AMZ-AUDIO-032"[^\n]*"type":"IMAGE","value":"\/demo\/products\/auralink-buds\.svg"/);
  assert.match(catalogue, /"merchantSku":"AMZ-SHOE-035"[^\n]*"type":"IMAGE","value":"\/demo\/products\/stride-white-low-top\.svg"/);
  assert.match(auralinkAsset, /<svg[\s\S]*AURALINK/);
  assert.match(strideAsset, /<svg[\s\S]*low-top sneaker/);
  assert.match(chat, /authoritativeImage\(product\.facts\)/);
  assert.match(chat, /data-has-media=\{Boolean\(image\)\}/);
  assert.match(chat, /<img src=\{image\} alt=\{name\} loading="eager"/);
  assert.doesNotMatch(chat, /AMZ-AUDIO-032[\s\S]{0,100}(?:image|svg)/i);
});
