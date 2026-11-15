import Image from "next/image";
import { cardTags, type FrameDesign } from "@glassworks/frame-card";
import { Chip, Chips, Row, StageChip, manage as m } from "./Manage";
import { CLARITY, clarityFor } from "@/lib/clarity";

/** The frames as Manage rows: render, summary, install tags, Clarity and stage. */
export function FrameRows({ frames }: { frames: FrameDesign[] }) {
  return frames.map((f) => {
    const c = clarityFor(f.id);
    return (
      <Row
        key={f.id}
        href={`/frames/${f.id}`}
        lead={f.render ? <Image className={m.thumb} src={`/frames/${f.id}/render.webp`} alt="" width={92} height={56} unoptimized /> : undefined}
        title={f.name}
        sub={f.summary}
        extra={
          <Chips>
            {cardTags(f.ratings).map((t) => (
              <Chip key={t.key}>{t.text}</Chip>
            ))}
          </Chips>
        }
        end={
          <>
            {c.state === "graded" && (
              <Chip>
                <b>
                  {CLARITY} {c.grade.grade}
                </b>
              </Chip>
            )}
            <StageChip support={f.status} />
          </>
        }
      />
    );
  });
}
