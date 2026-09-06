import type { Metadata } from "next";
import { AmanaProvider } from "@/components/amana/blade";
import { MerchantSessionProvider } from "@/components/merchant/merchant-session";

export const metadata: Metadata = {
  title: "Amana Merchant",
  description: "Prepare approved commerce sources for safe agentic commerce.",
};

export default function MerchantLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <AmanaProvider><MerchantSessionProvider>{children}</MerchantSessionProvider></AmanaProvider>;
}
