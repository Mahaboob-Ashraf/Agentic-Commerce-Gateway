import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { GeminiLiveLab } from "./gemini-live-lab";

export const metadata: Metadata = {
  title: "Gemini Live A/B lab · Agentic Commerce Gateway",
  description: "Developer-only Gemini 2.5 versus 3.1 Live voice-hardening POC",
};

export default function GeminiLiveLabPage() {
  if (process.env.NODE_ENV === "production") {
    notFound();
  }

  return <GeminiLiveLab />;
}
