import Link from "next/link";
import { MorphStory } from "@/components/home/MorphStory";
import { ListCard, manage as m } from "@/components/Manage";
import { FrameGrid } from "@/components/FrameGrid";
import { PaneRows } from "@/components/PaneRows";
import { KilnGlyph } from "@/components/Glyphs";
import { CLARITY, clarityFor, rubric } from "@/lib/clarity";
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
    detail: "Reads and saves your stock partitions to your computer before a single byte changes.",
  },
  {
    num: "02",
    title: "Verify",
    detail: "Hashes every image and backup on both sides so corrupted writes never reach flash.",
  },
  {
    num: "03",
    title: "Flash",
    detail: "Installs Glassworks and hands off Wi-Fi and Home Assistant setup straight from Chrome.",
  },
  {
    num: "04",
    title: "Restore",
    detail: "Keep your backup to return the hardware to stock firmware in one click at any time.",
  },
] as const;

export default async function Home() {
  const [[post], latest] = await Promise.all([getPosts(), release()]);
  // Beat 1 of the story draws this frame's own model, on its dock
  const tablet = frames.find((f) => f.id === "google-pixel-tablet");
  if (!tablet) throw new Error("frames/google-pixel-tablet is missing: the home page's story draws it");

  const clarityLetters = rubric.grade.letters.map((l) => l.letter);
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
        tablet={tablet}
      />

      <div className={s.below}>
        {/* 1. Supported Hardware */}
        <section className={s.band} aria-labelledby="devices-heading">
          <div className={s.sectionIntro}>
            <span className={s.eyebrow}>SUPPORTED HARDWARE</span>
            <h2 id="devices-heading" className={s.sectionH2}>
              Real wall hardware — and the tablet in your drawer
            </h2>
            <p className={s.sectionLede}>
              Dedicated wall switches, full-length mirrors, and everyday Android tablets running the same open Home Assistant panel core.
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
              Flash from Chrome, undo in one click
            </h2>
            <p className={s.sectionLede}>
              Plug your panel in over USB and open Kiln in Chrome or Edge. No command-line tools, no manual drivers, and no locked-in vendor cloud — every write is verified and every device keeps a clean way back to stock.
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
                <b>Glassworks · Kitchen Panel</b>
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
                <span>Charging dock</span>
                <b>Docked</b>
                <span className={s.mutedVal}>Speaker &amp; power</span>
              </div>
              <div className={s.esphomeRow}>
                <span>Presence</span>
                <b>ToF sensor</b>
                <span className={s.mutedVal}>Ignores light switches</span>
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
              Glassworks speaks the native ESPHome API directly to Home Assistant. Nothing installs in HACS, no custom integration is required, no cloud account sits in the middle, and panel updates arrive under <em>Settings → Updates</em>.
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
              We&rsquo;d rather show an honest C than hide it
            </h2>
            <p className={s.sectionLede}>
              Every supported panel is measured running the real Home Assistant frontend on standardized dashboards — testing tap latency, scrolling frame pacing, live state updates, and camera streams.
            </p>
            <div className={s.actions}>
              <Link href="/docs/perf" className={s.textLink}>
                How {CLARITY} grades work →
              </Link>
              <Link href="/frames" className={s.textLink}>
                Compare all devices →
              </Link>
            </div>
          </div>

          <div className={`glass ${s.clarityCard}`}>
            <div className={s.clarityScale} aria-label="Clarity grade scale from A+ to E">
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
                        : "Measurements pending"}
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
              Each capability ships as a modular pane that turns on automatically when your hardware supports it — plus ready-to-import Home Assistant blueprints.
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
              <h2>Port Glassworks to new glass — or build with our MIT libraries</h2>
              <p>
                Explore the 6-layer architecture, inspect the Pane API, grab our standalone MIT Kotlin and TypeScript libraries, or ship Glassworks on your own hardware under MPL-2.0.
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
