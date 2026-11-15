import Link from "next/link";
import { ListCard, manage as m } from "@/components/Manage";
import { type Blueprint, blueprints } from "@/lib/blueprints";
import s from "./Blueprints.module.css";

/** Blueprints as Manage rows: name and summary, the Add pill and the file. */
export function BlueprintRows({ list }: { list: Blueprint[] }) {
  return list.map((b) => (
    <div key={b.path} className={`${m.row} ${s.row}`}>
      <span className={m.rt}>
        <b>{b.name}</b>
        <span>{b.summary}</span>
      </span>
      <span className={s.acts}>
        <a className={m.btn} href={b.add} target="_blank" rel="noopener noreferrer">
          Add to Home Assistant
        </a>
        <a className={s.file} href={b.source} target="_blank" rel="noopener noreferrer">
          View the file
        </a>
      </span>
    </div>
  ));
}

/** A pane page's Blueprints card, or nothing when the release has none for [pane]. */
export async function PaneBlueprints({ pane }: { pane: string }) {
  const list = (await blueprints()).list.filter((b) => b.pane === pane);
  if (list.length === 0) return null;
  return (
    <ListCard id="blueprints" title="Blueprints" aside={<Link href="/blueprints">All blueprints</Link>}>
      <BlueprintRows list={list} />
    </ListCard>
  );
}
