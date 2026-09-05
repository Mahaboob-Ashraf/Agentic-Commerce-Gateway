import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import type { CSSProperties } from "react";
import styles from "./landing.module.css";

const assets = "/amana/landing";

export const metadata: Metadata = {
  title: "Amana — Commerce you can trust agents with",
  description: "Make stores agent-ready. Shop through AI with the safeguards of real commerce.",
};

function Brand() {
  return (
    <span className={styles.brand}>
      <Image alt="" aria-hidden="true" height={36} src={`${assets}/amana-mark.png`} width={36} />
      <span>Amana</span>
    </span>
  );
}

function Arrow() {
  return <span aria-hidden="true">↗</span>;
}

const merchantSteps = ["Inspect", "Understand", "Test", "Repair", "Agent Ready"];
const buyerSteps = ["Understand", "Retrieve", "Verify", "Propose", "Pay"];

export default function Home() {
  return (
    <main className={styles.landing}>
      <section className={styles.hero} aria-labelledby="hero-title" id="top">
        <nav className={styles.navigation} aria-label="Primary navigation">
          <a className={styles.brandLink} href="#top" aria-label="Amana home"><Brand /></a>
          <div className={styles.navLinks}>
            <a href="#how-it-works">How it works</a>
            <Link href="/buyer/chat">Buyer</Link>
            <a href="#merchant">Merchant</a>
          </div>
          <Link className={styles.navAction} href="/buyer/chat">Enter as Buyer <Arrow /></Link>
        </nav>

        <div className={styles.heroGrid}>
          <div className={styles.heroCopy}>
            <p className={styles.kicker}>Amana / Agentic commerce</p>
            <h1 id="hero-title">
              <span>Commerce you can</span>
              <span>trust agents with.</span>
            </h1>
            <p className={styles.heroSupport}>
              Make stores agent-ready. Shop through AI with the safeguards of real commerce.
            </p>
            <div className={styles.heroActions}>
              <Link className={styles.primaryAction} href="/buyer/chat">Enter as Buyer <Arrow /></Link>
              <a className={styles.textAction} href="#merchant">Enter as Merchant <Arrow /></a>
            </div>
          </div>

          <div className={styles.heroVisual} aria-label="Amana connects an agent-ready merchant store to a safe AI buyer." role="img">
            <div className={styles.visualField} aria-hidden="true" />
            <div className={styles.storeVisual} aria-hidden="true">
              <Image
                alt=""
                height={1147}
                loading="eager"
                sizes="(max-width: 767px) 58vw, (max-width: 1199px) 34vw, 28vw"
                src={`${assets}/hero-store.png`}
                width={1372}
              />
            </div>
            <div className={styles.orbVisual} aria-hidden="true">
              <Image
                alt=""
                height={1019}
                loading="eager"
                sizes="(max-width: 767px) 52vw, (max-width: 1199px) 30vw, 24vw"
                src={`${assets}/hero-buyer-orb.png`}
                width={1544}
              />
            </div>
            <div className={styles.heroConnection} aria-hidden="true">
              <span /><b>Trusted commerce layer</b><span />
            </div>
          </div>
        </div>

        <div className={styles.heroRail}>
          <p><span>01</span> Merchant Agentization Agent</p>
          <p><span>02</span> Safe AI Buyer</p>
          <a href="#how-it-works">Scroll to the system <span aria-hidden="true">↓</span></a>
        </div>
      </section>

      <section className={styles.system} aria-labelledby="system-title" id="how-it-works">
        <header className={styles.systemHeader}>
          <p className={styles.kicker}>01 / The system</p>
          <h2 id="system-title">One system. <span>Two agents.</span></h2>
          <p>Stores become agent-ready. Buyers transact safely.</p>
        </header>

        <div className={styles.storyLine} aria-hidden="true"><span>Agent-ready commerce layer</span></div>

        <article className={`${styles.story} ${styles.merchantStory}`} id="merchant">
          <div className={styles.storyCopy}>
            <p className={styles.storyMarker}>01 / Merchant</p>
            <h3>Turn a store into something an AI can safely act on.</h3>
            <p className={styles.storySupport}>
              Amana inspects products, policies and capabilities, tests them, repairs safe
              mismatches, and only publishes what is verified.
            </p>
            <ol className={styles.workflow} aria-label="Merchant agentization workflow">
              {merchantSteps.map((step, index) => <li key={step}><span>0{index + 1}</span>{step}</li>)}
            </ol>
          </div>
          <figure className={styles.merchantArchitecture}>
            <Image
              alt="Merchant agentization workflow from store data and rules through testing, repair, and verified agent-ready capability."
              height={1645}
              sizes="(max-width: 767px) 94vw, (max-width: 1199px) 52vw, 43vw"
              src={`${assets}/architecture-merchant.png`}
              width={956}
            />
          </figure>
        </article>

        <div className={styles.systemBridge} aria-hidden="true">
          <span>Verified capability</span><i /><span>Grounded intent</span>
        </div>

        <article className={`${styles.story} ${styles.buyerStory}`} id="buyer-system">
          <figure className={styles.buyerArchitecture}>
            <Image
              alt="Safe Buyer workflow from multimodal intent through retrieval, deterministic guardrails, user-approved Razorpay payment, and lifecycle support."
              height={1536}
              sizes="(max-width: 767px) 94vw, (max-width: 1199px) 52vw, 43vw"
              src={`${assets}/architecture-buyer.png`}
              width={1024}
            />
          </figure>
          <div className={styles.storyCopy}>
            <p className={styles.storyMarker}>02 / Buyer</p>
            <h3>From “find this for me” to a verified purchase.</h3>
            <p className={styles.storySupport}>
              Amana understands intent, retrieves across merchants, verifies price and safety,
              prepares an exact proposal, then lets the buyer approve.
            </p>
            <ol className={styles.workflow} aria-label="Safe Buyer workflow">
              {buyerSteps.map((step, index) => <li key={step}><span>0{index + 1}</span>{step}</li>)}
            </ol>
          </div>
        </article>
      </section>

      <section className={styles.experience} aria-labelledby="experience-title" id="ways-to-ask">
        <header className={styles.experienceHeader}>
          <p className={styles.kicker}>02 / The interaction</p>
          <h2 id="experience-title">Talk it. Show it. Buy it.</h2>
        </header>

        <article className={styles.talkMovement}>
          <div className={styles.talkCopy}>
            <p className={styles.storyMarker}>Talk / Gemini Live</p>
            <h3>Say what you need. Exactly how you&apos;d say it.</h3>
            <blockquote lang="hi-Latn">
              “₹500 ke andar high-protein vegetarian snacks chahiye. Peanuts bilkul nahi.”
            </blockquote>
            <p>Speak naturally. Switch languages. Interrupt anytime.</p>
            <ul aria-label="Voice capabilities"><li>Realtime</li><li>Multilingual</li><li>Interruptible</li></ul>
            <small>Voice uses the same Safe Buyer and deterministic safeguards.</small>
          </div>
          <div className={styles.liveOrb} aria-hidden="true">
            <span className={styles.orbOrbit} />
            <Image alt="" height={1019} sizes="(max-width: 767px) 64vw, 30vw" src={`${assets}/hero-buyer-orb.png`} width={1544} />
            <div className={styles.waveform}>
              {[18, 36, 52, 29, 64, 43, 24, 55, 33, 20].map((height, index) => (
                <i key={index} style={{ "--wave-height": `${height}px` } as CSSProperties} />
              ))}
            </div>
          </div>
        </article>

        <article className={styles.showMovement}>
          <div className={styles.showVisual}>
            <div className={styles.shoeVisual}>
              <Image
                alt="Blue running shoe used as a visual shopping reference."
                height={976}
                sizes="(max-width: 767px) 88vw, 44vw"
                src={`${assets}/hero-shoe-card.png`}
                width={1612}
              />
            </div>
            <p className={styles.visualRequest}>Find something like this under ₹4,000</p>
            <div className={styles.groundedResult}>
              <span>Merchant evidence / Verified</span>
              <strong>Running Shoes</strong>
              <p>₹2,999 · In stock</p>
              <b aria-label="Verified">✓</b>
            </div>
          </div>
          <div className={styles.showCopy}>
            <p className={styles.storyMarker}>Show / Product direction</p>
            <h3>Show Amana the thing you mean.</h3>
            <p>Start with an image. Amana grounds the request against real merchant evidence.</p>
            <strong>Vision proposes.<br />Merchant evidence verifies.</strong>
          </div>
        </article>
      </section>

      <section className={styles.entry} aria-labelledby="entry-title" id="entry">
        <div className={styles.entryWatermark} aria-hidden="true">
          <Image alt="" height={1254} src={`${assets}/amana-mark.png`} width={1254} />
        </div>
        <header>
          <p className={styles.kicker}>03 / Enter Amana</p>
          <h2 id="entry-title">Built for both sides<br />of commerce.</h2>
          <p>Agentic commerce you can trust.</p>
        </header>
        <div className={styles.entryLinks}>
          <Link href="/buyer/chat">
            <span>Buyer</span><strong>Shop through Amana</strong><Arrow />
          </Link>
          <a href="#merchant">
            <span>Merchant</span><strong>Make your store agent-ready</strong><Arrow />
          </a>
        </div>
        <footer><Brand /><span>Merchant Agentization Agent + Safe AI Buyer</span></footer>
      </section>
    </main>
  );
}
