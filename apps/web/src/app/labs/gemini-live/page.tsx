import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { GeminiLiveLab } from "./gemini-live-lab";

export const metadata: Metadata = {
  title: "Gemini Live lab · Agentic Commerce Gateway",
  description: "Developer-only Gemini 2.5 Live asynchronous function-calling POC",
};

export default function GeminiLiveLabPage() {
  if (process.env.NODE_ENV === "production") {
    notFound();
  }

  return <GeminiLiveLab />;
}
