import type { Metadata } from "next";
import { BuyerSessionProvider } from "@/components/buyer/buyer-session";
import { AmanaProvider } from "@/components/amana/amana-provider";

export const metadata: Metadata = {
  title: "Amana Buyer",
  description: "A grounded, buyer-controlled agentic commerce experience.",
};

export default function BuyerLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <AmanaProvider><BuyerSessionProvider>{children}</BuyerSessionProvider></AmanaProvider>;
}
