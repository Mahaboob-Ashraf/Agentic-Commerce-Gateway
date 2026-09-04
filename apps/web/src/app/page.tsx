import {
  AmanaActionLink,
  AmanaPage,
  AmanaSection,
  Status,
  Surface,
} from "@/components/amana";

export default function Home() {
  return (
    <AmanaPage>
      <AmanaSection
        eyebrow="Interface foundation"
        title="Amana"
        description="Agentic commerce you can trust."
      >
        <Surface padding="comfortable">
          <div className="flex flex-col items-start gap-6 sm:flex-row sm:items-end sm:justify-between">
            <div className="max-w-xl space-y-3">
              <Status tone="positive">Foundation ready</Status>
              <p className="text-sm leading-6 text-[var(--amana-ink-muted)] sm:text-base">
                Shared visual rules and accessible primitives are ready for the buyer and merchant
                experiences. Feature screens remain deliberately deferred.
              </p>
            </div>
            <AmanaActionLink href="/labs/gemini-live">Open voice lab</AmanaActionLink>
          </div>
        </Surface>
      </AmanaSection>
    </AmanaPage>
  );
}
