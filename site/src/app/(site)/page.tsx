import Link from "next/link";
import { MorphStory } from "@/components/home/MorphStory";
import { ListCard, manage as m } from "@/components/Manage";
import { FrameGrid } from "@/components/FrameGrid";
import { PaneRows } from "@/components/PaneRows";
import { KilnGlyph } from "@/components/Glyphs";
import { CLARITY, clarityFor, rubric } from "@/lib/clarity";
import { blueprints } from "@/lib/blueprints";
import { frames, KILN } from "@/lib/frames";
import { release } from "@/lib/release";
import { panes } from "@/lib/site";
import { JsonLd } from "@/components/JsonLd";
import { SITE_DESCRIPTION, organizationLd, pageMetadata, softwareLd, websiteLd } from "@/lib/seo";
import { DraftFlag, PostCover, longDate } from "@/components/blog/PostBits";
import { getPosts } from "@/lib/docs";
import b from "@/components/blog/Blog.module.css";
import s from "./page.module.css";

export const metadata = pageMetadata({
  title: "Glassworks: your home, on the wall",
  absoluteTitle: true,
  description: SITE_DESCRIPTION,
  path: "/",
});

const KILN_STAGES = [
  {
    num: "01",
    title: "Backup",
    detail: "Saves your stock firmware to your computer before a single byte changes on panels that support it. (The Mirror's Glassworks image replaces lululemon's software with no stock backup, while its experimental install on the stock software saves a restore point first.)",
  },
  {
    num: "02",
    title: "Verify",
    detail: "Checks every download before flashing—and on the S1, reads every written partition back to confirm it matches byte for byte.",
  },
  {
    num: "03",
    title: "Flash",
    detail: "Installs Glassworks and walks you straight into Wi-Fi and Home Assistant setup from the browser.",
  },
  {
    num: "04",
    title: "Restore",
    detail: "Return the S1 to your saved stock backup with a guided restore. Switch the Mirror back to plain Glass Panes anytime, or to lululemon's system with the restore point its stock-software install saves.",
  },
] as const;

export default async function Home() {
  const [posts, latest, catalog] = await Promise.all([getPosts(), release(), blueprints()]);
  const clarityPost = posts.find((p) => p.slug === "clarity");
  // When Clarity launches alongside another post on the same day, feature the other post in the hero
  // because the Clarity section below already links straight to Introducing Clarity.
  const post = posts.find((p) => p.date === posts[0]?.date && p.slug !== "clarity") ?? posts[0];
  // the story draws these frames' own models
  const frame = (id: string) => {
    const f = frames.find((x) => x.id === id);
    if (!f) throw new Error(`frames/${id} is missing: the home page's story draws it`);
    return f;
  };
  const storyFrames = { s1: frame("aqara-s1-plus"), mirror: frame("lululemon-mirror"), tablet: frame("google-pixel-tablet") };

  // every letter Clarity gives, the fallback (E) last
  const clarityLetters = [...rubric.grade.letters.map((l) => l.letter), rubric.grade.fallback.letter];
  const clarityScale = `${clarityLetters[0]} to ${clarityLetters[clarityLetters.length - 1]}`;
  const clarityRows = frames.map((f) => ({
    frame: f,
    clarity: clarityFor(f.id),
  }));

  return (
    <>
      <JsonLd nodes={[organizationLd(), websiteLd(), softwareLd(SITE_DESCRIPTION)]} />

      <MorphStory
        release={{ version: latest.version, href: latest.href }}
        post={post?.fresh ? { title: post.title, href: post.url } : undefined}
        frames={storyFrames}
        blueprints={catalog.list.map((b) => b.path)}
      />

      <div className={s.below}>
        {/* 1. Supported Hardware */}
        <section className={s.band} aria-labelledby="devices-heading">
          <div className={s.sectionIntro}>
            <span className={s.eyebrow}>SUPPORTED HARDWARE</span>
            <h2 id="devices-heading" className={s.sectionH2}>
              Real wall hardware—and the tablet in your drawer
            </h2>
            <p className={s.sectionLede}>
              Turn dedicated wall switches, full-length smart mirrors, and everyday Android tablets into fast, local Home Assistant panels.
            </p>
          </div>
          <FrameGrid
            id="devices"
            title="Supported devices"
            aside={<Link href="/frames">All devices ›</Link>}
            frames={frames}
          />
        </section>

        {/* 2. Install Safely with Kiln (Dedicated 2-Column Section) */}
        <section className={s.split} aria-labelledby="kiln-heading">
          <div className={s.splitCopy}>
            <span className={`${s.eyebrow} ${s.eyebrowEmber}`}>KILN WEB INSTALLER</span>
            <h2 id="kiln-heading" className={s.sectionH2}>
              Install from your browser, with a way back
            </h2>
            <p className={s.sectionLede}>
              Plug in over USB and open Kiln in Chrome or Edge—no terminal commands and no vendor cloud (just a quick{" "}
              <Link href="/docs/frames/aqara-s1-plus/install#one-time-setup-for-your-computer">one-time USB driver</Link> for the S1 on Windows). Supported wall switches back up their stock software first so you can return anytime, while the Studio Mirror can always go back to plain Glass Panes, or to lululemon&rsquo;s system if you installed on the stock software.
            </p>
            <div className={s.actions}>
              <a href={KILN} className={s.kilnBtn}>
                <KilnGlyph />
                Open Kiln ↗
              </a>
              <Link href="/docs/kiln" className={s.textLink}>
                How Kiln works →
              </Link>
            </div>
          </div>

          <ol className={`glass ${s.pipeline}`} aria-label="Kiln four-stage safety pipeline">
            {KILN_STAGES.map((stage) => (
              <li key={stage.num} className={s.pipelineStep}>
                <span className={s.stepNum}>{stage.num}</span>
                <div className={s.stepBody}>
                  <h3>{stage.title}</h3>
                  <p>{stage.detail}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        {/* 3. Built Native for Home Assistant (Dedicated 2-Column Section) */}
        <section className={`${s.split} ${s.splitFlip}`} aria-labelledby="esphome-heading">
          <div className={`glass ${s.esphomeCard}`} aria-label="Example Home Assistant ESPHome device card">
            <div className={s.esphomeHead}>
              <div>
                <b>Glassworks · Kitchen Tablet</b>
                <span>ESPHome Native API · Local</span>
              </div>
              <span className={s.statusChip}>
                <span className={s.dotGreen} aria-hidden="true" />
                Connected
              </span>
            </div>

            <div className={s.esphomeRows}>
              <div className={s.esphomeRow}>
                <span>Update</span>
                <b>{latest.version}</b>
                <span className={s.litBadge}>Up to date</span>
              </div>
              <div className={s.esphomeRow}>
                <span>Docked</span>
                <b>On</b>
                <span className={s.mutedVal}>Binary sensor</span>
              </div>
              <div className={s.esphomeRow}>
                <span>Camera presence</span>
                <b>Clear</b>
                <span className={s.mutedVal}>Motion in view</span>
              </div>
              <div className={s.esphomeRow}>
                <span>Ambient light</span>
                <b>142 lx</b>
                <span className={s.mutedVal}>Auto-dim active</span>
              </div>
              <div className={s.esphomeRow}>
                <span>Assist microphone</span>
                <b>Enabled</b>
                <span className={s.toggleOn} aria-hidden="true">
                  <span />
                </span>
              </div>
              <div className={s.esphomeRow}>
                <span>Screen brightness</span>
                <b>80%</b>
                <span className={s.miniBar} aria-hidden="true">
                  <span style={{ width: "80%" }} />
                </span>
              </div>
            </div>
          </div>

          <div className={s.splitCopy}>
            <span className={s.eyebrow}>ESPHOME NATIVE</span>
            <h2 id="esphome-heading" className={s.sectionH2}>
              Updates, sensors, and settings right where they belong
            </h2>
            <p className={s.sectionLede}>
              Glassworks speaks the native ESPHome API straight to Home Assistant. There is nothing to install in HACS, no custom integration to maintain, and no cloud account in the middle—even updates show up right under <em>Settings → Updates</em>.
            </p>
            <div className={s.actions}>
              <Link href="/docs/esphome" className={s.textLink}>
                Explore the ESPHome entities →
              </Link>
              <Link href="/docs/principles" className={s.textLink}>
                Read our principles →
              </Link>
            </div>
          </div>
        </section>

        {/* 4. Honest Speed with Clarity (Dedicated Section) */}
        <section className={s.split} aria-labelledby="clarity-heading">
          <div className={s.splitCopy}>
            <span className={s.eyebrow}>CLARITY BENCHMARK</span>
            <h2 id="clarity-heading" className={s.sectionH2}>
              We&rsquo;d rather show an honest grade than hide it
            </h2>
            <p className={s.sectionLede}>
              We benchmark panels on the real Home Assistant frontend—measuring tap latency, scroll smoothness, live state updates, and camera streams on standardized dashboards so you know what to expect before you buy. Devices without a published run stay marked pending until tested.
            </p>
            <div className={s.actions}>
              <Link href={clarityPost ? clarityPost.url : "/docs/clarity"} className={s.textLink}>
                {clarityPost ? `Introducing ${CLARITY} →` : `How ${CLARITY} grades work →`}
              </Link>
              <Link href="/frames" className={s.textLink}>
                Compare all devices →
              </Link>
            </div>
          </div>

          <div className={`glass ${s.clarityCard}`}>
            <div className={s.clarityScale} aria-label={`${CLARITY} grade scale from ${clarityScale}`}>
              <span className={s.clarityScaleLabel}>{CLARITY} scale</span>
              <div className={s.letterStrip}>
                {clarityLetters.map((letter) => (
                  <span key={letter} className={s.letterPill}>
                    {letter}
                  </span>
                ))}
              </div>
            </div>

            <div className={s.clarityRows}>
              {clarityRows.map(({ frame, clarity }) => (
                <Link key={frame.id} href={`/frames/${frame.id}`} className={s.clarityRow}>
                  <div className={s.clarityMeta}>
                    <b>{frame.name}</b>
                    <span>
                      {clarity.state === "graded"
                        ? clarity.grade.meaning ?? `${CLARITY} ${clarity.grade.grade}`
                        : clarity.state === "none"
                          ? "Measurements pending"
                          : clarity.state === "re-measure"
                            ? "Needs a fresh Clarity run"
                            : clarity.state === "not-comparable"
                              ? clarity.grade.why || "Not comparable with current Clarity runs"
                              : "Measured on an older Clarity rubric"}
                    </span>
                  </div>
                  {clarity.state === "graded" ? (
                    <span className={`${s.gradeBadge} ${s.gradeGraded}`}>
                      {clarity.grade.grade}
                    </span>
                  ) : (
                    <span className={s.gradeBadge}>—</span>
                  )}
                </Link>
              ))}
            </div>
          </div>
        </section>

        {/* 5. Everything Your Panel Can Do */}
        <section className={s.band} aria-labelledby="panes-heading">
          <div className={s.sectionIntro}>
            <span className={s.eyebrow}>PANES &amp; BLUEPRINTS</span>
            <h2 id="panes-heading" className={s.sectionH2}>
              Everything your panel can do
            </h2>
            <p className={s.sectionLede}>
              Features ship as modular panes matched to your hardware. Core capabilities work out of the box, optional ones like the Bluetooth proxy can be toggled on whenever you want them, and ready-to-import blueprints make automations effortless.
            </p>
          </div>
          <ListCard
            id="panes"
            title="Built-in panes"
            aside={
              <span className={s.cardLinks}>
                <Link href="/blueprints">Blueprints</Link>
                <span aria-hidden="true">·</span>
                <Link href="/panes">All features ›</Link>
              </span>
            }
          >
            <PaneRows panes={panes} />
          </ListCard>
        </section>

        {/* 6. Latest from the Blog + Builders & Open Core (MPL-2.0) Callout */}
        <section className={s.band} aria-label="Blog and developer open core">
          {post && (
            <Link href={post.url} className={`${m.card} ${b.homeCard}`}>
              <PostCover post={post} />
              <div>
                <div className={b.meta}>
                  {post.draft && <DraftFlag />}
                  <span>Blog</span>
                  <time dateTime={post.date}>{longDate(post.date)}</time>
                </div>
                <h2>{post.title}</h2>
                <p>{post.summary}</p>
                <span className={b.read}>Read the post</span>
              </div>
            </Link>
          )}

          <div className={`glass ${s.devCallout}`}>
            <div className={s.devCopy}>
              <span className={s.eyebrow}>OPEN CORE · MPL-2.0</span>
              <h2>Port Glassworks to new glass—or build with our MIT libraries</h2>
              <p>
                Bringing up a new wall panel or building smart-home software of your own? Explore the 6-layer architecture, browse the Pane API, reuse our standalone MIT libraries, or ship Glassworks on your hardware under MPL-2.0.
              </p>
            </div>
            <div className={s.devActions}>
              <Link href="/developers" className={s.devPrimary}>
                For developers →
              </Link>
              <Link href="/docs/frames" className={s.textLink}>
                Add a device
              </Link>
            </div>
          </div>
        </section>
      </div>
    </>
  );
}
