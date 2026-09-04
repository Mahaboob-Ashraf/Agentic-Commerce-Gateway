"use client";

import { BladeProvider } from "@razorpay/blade/components";
import { bladeTheme } from "@razorpay/blade/tokens";
import type { ReactNode } from "react";

type AmanaProviderProps = {
  children: ReactNode;
};

export function AmanaProvider({ children }: AmanaProviderProps) {
  return (
    <BladeProvider colorScheme="light" themeTokens={bladeTheme}>
      <div className="amana-root">{children}</div>
    </BladeProvider>
  );
}
