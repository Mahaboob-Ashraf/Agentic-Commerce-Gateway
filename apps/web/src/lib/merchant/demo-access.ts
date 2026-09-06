export type MerchantDemoCredentials = Readonly<{
  identity: string;
  password: string;
}>;

export function configuredDemoMerchant(
  identity: string | undefined,
  password: string | undefined,
): MerchantDemoCredentials | null {
  const normalizedIdentity = identity?.trim();
  if (!normalizedIdentity || !password) return null;
  return { identity: normalizedIdentity, password };
}
