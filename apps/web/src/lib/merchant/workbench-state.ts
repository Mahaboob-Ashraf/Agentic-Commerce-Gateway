import type { ManifestCapability } from "./types";
import type { WorkbenchCapabilityState } from "./workbench-replay";

/** Maps reducer-owned manifest data without promoting unadvertised or absent capabilities. */
export function manifestCapabilityState(
  capability: ManifestCapability | undefined,
): WorkbenchCapabilityState {
  if (!capability) return "NOT_CONFIGURED";
  if (capability.readiness === "READY" && capability.advertised) return "READY";
  if (capability.readiness === "BLOCKED") return "BLOCKED";
  return "UNKNOWN";
}
