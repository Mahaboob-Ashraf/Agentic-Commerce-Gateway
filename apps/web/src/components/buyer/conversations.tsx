"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { buyerApi } from "@/lib/buyer/api";
import type { CommerceThread, ThreadMessage } from "@/lib/buyer/types";
import { Icon } from "./icons";
import styles from "./workspace.module.css";

export function ConversationHistory() {
  const [threads, setThreads] = useState<CommerceThread[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { buyerApi.threads().then(setThreads).catch((caught: Error) => setError(caught.message)); }, []);
  return (
    <div className={styles.page}><div className={styles.pageNarrow}>
      <header className={styles.pageHeader}><div><p className={styles.eyebrow}>Your conversations</p><h1>History, without the clutter.</h1><p>Return to earlier buyer requests and their last authoritative state.</p></div><Link className="amana-inline-action" href="/buyer/chat">New conversation</Link></header>
      {error && <p className={styles.loading} role="alert">Could not load conversations: {error}</p>}
      {!threads && !error && <p className={styles.loading}>Loading conversations…</p>}
      {threads?.length === 0 && <div className={styles.emptyList}><p>No conversations yet. Your first request will begin here.</p><Link className="amana-inline-action" href="/buyer/chat">Start a new chat</Link></div>}
      {threads && threads.length > 0 && <div className={styles.conversationList}>{threads.map((thread) => <Link className={styles.conversationRow} href={`/buyer/chat/${thread.threadId}`} key={thread.threadId}><div><h2>{thread.title}</h2><p>{friendlyState(thread.state)} · {thread.stepCount} steps</p></div><time dateTime={thread.updatedAt}>{formatDate(thread.updatedAt)}</time><Icon name="chevron" /></Link>)}</div>}
    </div></div>
  );
}

export function ConversationDetail({ threadId }: { threadId: string }) {
  const [thread, setThread] = useState<CommerceThread | null>(null);
  const [messages, setMessages] = useState<ThreadMessage[]>([]);
  const [error, setError] = useState("");
  useEffect(() => { Promise.all([buyerApi.thread(threadId), buyerApi.messages(threadId)]).then(([nextThread, nextMessages]) => { setThread(nextThread); setMessages(nextMessages); }).catch((caught: Error) => setError(caught.message)); }, [threadId]);
  return <div className={styles.page}><div className={styles.pageNarrow}>
    <header className={styles.threadHeader}><Link className={styles.backLink} href="/buyer/conversations"><Icon name="arrow" />All conversations</Link>{thread ? <><h1>{thread.title}</h1><span className={styles.threadState}>{friendlyState(thread.state)}</span></> : <h1>{error ? "Conversation unavailable" : "Loading conversation…"}</h1>}</header>
    {error && <p className={styles.loading} role="alert">{error}</p>}
    <div className={styles.messages}>{messages.map((message) => <article className={styles.message} key={message.messageId}><p>{message.normalizedText}</p><time dateTime={message.createdAt}>{formatDate(message.createdAt)}</time></article>)}</div>
    <p className={styles.readOnlyNotice}>This is a read-only thread view in C1. Continuing commerce conversations arrives in C2.</p>
  </div></div>;
}

function friendlyState(value: string) { return value.toLowerCase().split("_").map((part) => part[0]?.toUpperCase() + part.slice(1)).join(" "); }
function formatDate(value: string) { return new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }).format(new Date(value)); }

export function PlaceholderPage({ kind }: { kind: "orders" | "autobuy" }) {
  const content = kind === "orders" ? { icon: "orders" as const, eyebrow: "Orders", title: "Every order, one clear timeline.", body: "Tracking and carefully authorized lifecycle actions will live here after the commerce experience is connected." } : { icon: "autobuy" as const, eyebrow: "AutoBuy", title: "Repeat purchases, within your rules.", body: "Future AutoBuy plans will pause whenever current price, identity, safety, or authority cannot be verified." };
  return <section className={styles.placeholder}><div className={styles.placeholderGlyph}><Icon name={content.icon} /></div><p className={styles.eyebrow}>{content.eyebrow} · Coming next</p><h1>{content.title}</h1><p>{content.body}</p><div className={styles.truthNote}><Icon name="shield" /><span>This surface is intentionally navigation-ready only. It does not simulate orders, scheduling, or payment authority.</span></div><div className={styles.placeholderRule} /></section>;
}
