import type { LiveTranscription } from "../gemini-live/socket";

export type VoiceTranscriptRole = "USER" | "AMANA";
export type VoiceTranscriptTurn = {
  id: string;
  role: VoiceTranscriptRole;
  text: string;
  final: boolean;
  languageCode?: string;
};

const INTERNAL_TOOL_SYNTAX = /(?:call\s*:\s*)?start_commerce_request\s*(?:\(|\{|:)/i;

export function visibleAssistantTranscript(value: string): string {
  const match = INTERNAL_TOOL_SYNTAX.exec(value);
  return (match ? value.slice(0, match.index) : value).trim();
}

export function mergeLiveTranscript(current: string, incoming: string): string {
  if (!current) return incoming;
  if (!incoming) return current;
  if (incoming.startsWith(current)) return incoming;
  if (current.endsWith(incoming)) return current;
  const maximum = Math.min(current.length, incoming.length);
  for (let overlap = maximum; overlap > 0; overlap -= 1) {
    if (current.endsWith(incoming.slice(0, overlap))) return current + incoming.slice(overlap);
  }
  const readableBoundary = /[\p{L}\p{N},.!?;:]$/u.test(current) && /^[\p{L}\p{N}]/u.test(incoming) ? " " : "";
  return current + readableBoundary + incoming;
}

/**
 * Gemini transcription `finished` marks a transcription segment, not necessarily a complete
 * assistant model turn. User turns may close on finished/VAD/model response; assistant turns close
 * only on the model generation/turn boundary. Output audio transcription is the sole visible
 * assistant transcript source.
 */
export class VoiceTranscriptTurns {
  private turns: VoiceTranscriptTurn[] = [];
  private drafts: Record<VoiceTranscriptRole, string | null> = { USER: null, AMANA: null };
  private readonly createId: () => string;

  constructor(createId: () => string = () => crypto.randomUUID()) {
    this.createId = createId;
  }

  beginUserTurn(): void {
    this.finalize("USER");
    this.finalize("AMANA");
  }

  beginAssistantTurn(): void {
    this.finalize("USER");
  }

  ingest(role: VoiceTranscriptRole, item: LiveTranscription, interim = false): void {
    let incoming = item.text?.trim();
    if (!incoming) return;
    if (role === "AMANA") {
      incoming = visibleAssistantTranscript(incoming);
      if (!incoming) return;
    }
    const existing = this.turns.find((turn) => turn.id === this.drafts[role]);
    if (existing) {
      existing.text = interim ? incoming : mergeLiveTranscript(existing.text, incoming);
      existing.languageCode = item.languageCode ?? existing.languageCode;
      existing.final = false;
    } else {
      const turn: VoiceTranscriptTurn = {
        id: this.createId(),
        role,
        text: incoming,
        final: false,
        languageCode: item.languageCode,
      };
      this.turns.push(turn);
      this.drafts[role] = turn.id;
    }
    if (role === "USER" && item.finished) this.finalize("USER");
  }

  finishModelTurn(): void {
    this.finalize("USER");
    this.finalize("AMANA");
  }

  finalize(role: VoiceTranscriptRole): void {
    const id = this.drafts[role];
    const turn = this.turns.find((candidate) => candidate.id === id);
    if (turn) turn.final = true;
    this.drafts[role] = null;
  }

  snapshot(limit = 80): VoiceTranscriptTurn[] {
    if (this.turns.length > limit) this.turns = this.turns.slice(-limit);
    return this.turns.map((turn) => ({ ...turn }));
  }

  clear(): void {
    this.turns = [];
    this.drafts = { USER: null, AMANA: null };
  }
}
