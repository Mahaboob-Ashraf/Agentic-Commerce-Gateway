import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { BUYER_IMAGE_MAX_BYTES, detectMime, validateBuyerImage } from "./image-upload.ts";

function png() { const bytes = new Uint8Array(24); bytes.set([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10]); bytes.set([0x49, 0x48, 0x44, 0x52], 12); return bytes; }
function webp() { return new Uint8Array([...Buffer.from("RIFF0000WEBPVP8X"), ...new Uint8Array(16)]); }

test("accepts only matching JPEG, PNG, and WebP content", async () => {
  assert.equal(detectMime(png()), "image/png");
  assert.equal(detectMime(new Uint8Array([0xff, 0xd8, 0xff])), "image/jpeg");
  assert.equal(detectMime(webp()), "image/webp");
  await validateBuyerImage(new File([png()], "reference.any-extension", { type: "image/png" }));
});

test("rejects SVG, mismatched content, and files over five MiB", async () => {
  await assert.rejects(validateBuyerImage(new File(["<svg/>"] , "unsafe.svg", { type: "image/svg+xml" })), /JPEG, PNG, or WebP/);
  await assert.rejects(validateBuyerImage(new File([new Uint8Array([0xff, 0xd8, 0xff])], "fake.png", { type: "image/png" })), /does not match/);
  await assert.rejects(validateBuyerImage(new File([new Uint8Array(BUYER_IMAGE_MAX_BYTES + 1)], "large.png", { type: "image/png" })), /5 MB/);
});
