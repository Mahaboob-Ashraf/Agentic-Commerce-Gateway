"use client";

import { useState } from "react";
import { Icon } from "./icons";
import styles from "./workspace.module.css";

const prompts = [
  "Find wireless earphones under ₹3,500",
  "I need high-protein vegetarian snacks with no peanuts",
  "Find something similar to this image",
];

export function NewChat() {
  const [value, setValue] = useState("");
  const [hint, setHint] = useState("Composer preview · Safe Buyer wiring arrives in C2");
  return (
    <div className={styles.chatPage}>
      <section className={styles.emptyState} aria-labelledby="new-chat-title">
        <div className={styles.orb} aria-hidden="true" />
        <p className={styles.eyebrow}>A thoughtful place to begin</p>
        <h1 id="new-chat-title">What can Amana find for you?</h1>
        <p className={styles.emptyLead}>Describe what matters—budget, fit, ingredients, or a specific product. Amana will ground the answer before anything moves forward.</p>
        <div className={styles.promptList} aria-label="Example prompts">
          {prompts.map((prompt) => <button key={prompt} onClick={() => setValue(prompt)} type="button">{prompt}</button>)}
        </div>
      </section>
      <div className={styles.composerWrap} data-tour="composer">
        <div className={styles.composer}>
          <textarea aria-label="Message Amana" onChange={(event) => setValue(event.target.value)} placeholder="Tell Amana what you’re looking for…" value={value} />
          <div className={styles.composerBar}>
            <button className={styles.iconButton} onClick={() => setHint("Image shopping is prepared for a later Buyer task.")} type="button" aria-label="Attach an image"><Icon name="image" /></button>
            <button className={styles.iconButton} onClick={() => setHint("Production voice will be connected after this shell review.")} type="button" aria-label="Start voice input"><Icon name="mic" /></button>
            <span className={styles.inputStatus} aria-live="polite">{hint}</span>
            <button className={styles.sendButton} disabled type="button" aria-label="Send unavailable in this preview"><Icon name="arrow" /></button>
          </div>
        </div>
        <p className={styles.composerNote}>No request will be sent from this preview.</p>
      </div>
    </div>
  );
}
