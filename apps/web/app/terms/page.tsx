import type { Metadata } from "next";
import { Legal } from "../components/legal";

export const metadata: Metadata = { title: "Terms" };

const CONTACT = process.env.NEXT_PUBLIC_CONTACT_EMAIL;

export default function TermsPage() {
  return (
    <Legal title="Terms" updated="6 October 2026">
      <h2>The notebook</h2>
      <p>
        Daily Brief is a planner: you write tasks, it reads your calendar if you connect it, and it proposes when to do the work. A plan is a proposal. Nothing is placed until you apply it, and you decide what to do with your day.
      </p>

      <h2>Your account and your words</h2>
      <p>
        You sign in with a Google account and are responsible for keeping it secure. What you write stays yours. You give Daily Brief permission to store and process it only to run the notebook for you.
      </p>

      <h2>Fair use</h2>
      <p>
        Do not use the service to break the law, to get at someone else’s account, or to overload or probe it. Accounts that do may be closed.
      </p>

      <h2>Paid plans</h2>
      <p>
        Some features need a paid plan. Prices are shown before you pay, payment is handled by Stripe, and you can cancel at any time; the plan runs to the end of the period already paid for.
      </p>

      <h2>No warranty</h2>
      <p>
        The service is provided as it is. A planner can be wrong: check anything that matters against your own calendar. To the extent the law allows, Daily Brief is not liable for losses that follow from relying on a plan, from a missed sync, or from time the service is unavailable.
      </p>

      <h2>Leaving</h2>
      <p>
        You can delete your account and everything in it from Settings at any time. These terms may change; a material change is announced in the app before it applies.
      </p>

      {CONTACT && (
        <p>
          Questions: <a href={`mailto:${CONTACT}`}>{CONTACT}</a>.
        </p>
      )}
    </Legal>
  );
}
