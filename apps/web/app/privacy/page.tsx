import type { Metadata } from "next";
import { Legal } from "../components/legal";

export const metadata: Metadata = { title: "Privacy" };

// The operator's contact address, set per deployment. Google's review asks for one.
const CONTACT = process.env.NEXT_PUBLIC_CONTACT_EMAIL;

/*
 * Written to match what the code does, not what a template says. If a scope, a stored
 * field or a third party changes, this page changes with it: services/server's
 * GoogleScopes and Notebook.kt are the facts it describes.
 */
export default function PrivacyPage() {
  return (
    <Legal title="Privacy" updated="6 October 2026">
      <h2>What Daily Brief keeps</h2>
      <ul>
        <li>Your Google account’s name and email address, to know whose notebook this is.</li>
        <li>The tasks, projects, working hours and plans you write.</li>
        <li>If you connect your calendar: the title, time, place and description of your events from about a week back to two months ahead.</li>
        <li>The access Google grants, stored encrypted, so the calendar can be read and the Drive copy written while you are away.</li>
      </ul>

      <h2>What it asks Google for, and why</h2>
      <ul>
        <li><strong>Who you are</strong> (name and email), when you sign in. Nothing else is asked for then.</li>
        <li><strong>Your calendar, read only</strong>, when you choose Connect calendar. Events are read so that plans fit around them. Daily Brief never creates, changes or deletes an event in your own calendars.</li>
        <li><strong>A calendar of its own</strong>, when you choose Plan in Calendar. Daily Brief creates a calendar called “Daily Brief” and writes your planned work into it. It can see and change only calendars it created; this permission does not let it read or change your own.</li>
        <li><strong>One file in your Drive</strong>, when you choose Connect Drive. Daily Brief can see and change only the file it creates there, “Daily Brief.md”, a readable copy of your notebook. It cannot see any other file.</li>
      </ul>
      <p>
        Daily Brief’s use of information received from Google APIs adheres to the{" "}
        <a href="https://developers.google.com/terms/api-services-user-data-policy" rel="noreferrer">Google API Services User Data Policy</a>, including the Limited Use requirements.
        Google data is used only to show you your own schedule and plan around it. It is not sold, not used for advertising, and not used to train AI models.
      </p>

      <h2>The written brief</h2>
      <p>
        When you ask for today’s brief, up to six of the day’s event titles, and counts of your planned work and busy and free time, are sent to Google’s Gemini API to write a few lines of summary. Nothing is sent unless you press the button.
      </p>

      <h2>On your device</h2>
      <p>
        Your browser keeps a sign-in cookie, your choice of ink and paper, the last pages you saw so the notebook opens quickly and offline, and anything jotted while offline until it can be sent. Signing out or deleting your account clears them. There are no advertising or analytics trackers.
      </p>

      <h2>Taking it away</h2>
      <p>
        Settings, Account, Delete account removes every task, plan, project and calendar event held for you and withdraws Daily Brief’s access at Google, at once. You can also withdraw access yourself at{" "}
        <a href="https://myaccount.google.com/permissions" rel="noreferrer">myaccount.google.com/permissions</a>. The copy in your Drive and the “Daily Brief” calendar are yours and stay until you delete them.
      </p>

      {CONTACT && (
        <>
          <h2>Questions</h2>
          <p>
            Write to <a href={`mailto:${CONTACT}`}>{CONTACT}</a>.
          </p>
        </>
      )}
    </Legal>
  );
}
