import Link from "next/link";

/** A printed page of the notebook: plain words, for anyone, signed in or not. */
export function Legal({ title, updated, children }: { title: string; updated: string; children: React.ReactNode }) {
  return (
    <article className="legal">
      <p className="legal-back">
        <Link href="/">Daily Brief</Link>
      </p>
      <h1>{title}</h1>
      <p className="page-date">Last updated {updated}</p>
      {children}
    </article>
  );
}
