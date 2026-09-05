export const BUYER_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const BUYER_IMAGE_ACCEPT = "image/jpeg,image/png,image/webp";

const allowed = new Set(["image/jpeg", "image/png", "image/webp"]);

export async function validateBuyerImage(file: File): Promise<void> {
  if (!allowed.has(file.type.toLowerCase())) {
    throw new Error("Choose a JPEG, PNG, or WebP image.");
  }
  if (file.size <= 0) throw new Error("The selected image is empty.");
  if (file.size > BUYER_IMAGE_MAX_BYTES) throw new Error("Product images must be 5 MB or smaller.");
  const head = new Uint8Array(await file.slice(0, 32).arrayBuffer());
  const detected = detectMime(head);
  if (detected !== file.type.toLowerCase()) {
    throw new Error("The selected file content does not match its image type.");
  }
}

export function detectMime(bytes: Uint8Array): string | null {
  if (bytes.length >= 24 && bytes[0] === 0x89 && ascii(bytes, 1, 3) === "PNG" && ascii(bytes, 12, 4) === "IHDR") return "image/png";
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 16 && ascii(bytes, 0, 4) === "RIFF" && ascii(bytes, 8, 4) === "WEBP" && ["VP8 ", "VP8L", "VP8X"].includes(ascii(bytes, 12, 4))) return "image/webp";
  return null;
}

function ascii(bytes: Uint8Array, offset: number, length: number) {
  return String.fromCharCode(...bytes.slice(offset, offset + length));
}
