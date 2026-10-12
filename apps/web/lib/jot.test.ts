import { describe, expect, it } from "vitest";
import { jotLines, parseJot } from "./jot";

const sat = new Date(2026, 9, 3, 11, 30).getTime(); // Saturday 3 October 2026, 11:30 local
const endOf = (y: number, m: number, d: number) => new Date(y, m, d + 1).getTime() - 1;
const projects = [
  { id: "p1", name: "Northwind rebrand" },
  { id: "p2", name: "Harbor app audit" },
  { id: "p3", name: "Quarterly taxes" },
];

describe("parseJot", () => {
  it("keeps a plain line as the title and nothing else", () => {
    expect(parseJot("Browser acceptance task", sat)).toEqual({ title: "Browser acceptance task" });
  });

  it("reads effort written several ways", () => {
    expect(parseJot("Call dentist 30m", sat).effortMinutes).toBe(30);
    expect(parseJot("Write report 2h", sat).effortMinutes).toBe(120);
    expect(parseJot("Write report 1h30m", sat).effortMinutes).toBe(90);
    expect(parseJot("Write report 1.5h", sat).effortMinutes).toBe(90);
    expect(parseJot("Write report 45 min", sat)).toMatchObject({ title: "Write report", effortMinutes: 45 });
    expect(parseJot("Write report 2 hours", sat)).toMatchObject({ title: "Write report", effortMinutes: 120 });
  });

  it("reads a due day, today included, as the end of that day", () => {
    expect(parseJot("Send invoice today", sat).dueAt).toBe(endOf(2026, 9, 3));
    expect(parseJot("Send invoice tomorrow", sat).dueAt).toBe(endOf(2026, 9, 4));
    expect(parseJot("Send invoice fri", sat).dueAt).toBe(endOf(2026, 9, 9));
    expect(parseJot("Send invoice sat", sat).dueAt).toBe(endOf(2026, 9, 3));
    expect(parseJot("Send invoice by thursday", sat)).toMatchObject({ title: "Send invoice", dueAt: endOf(2026, 9, 8) });
  });

  it("reads calendar dates, rolling into next year once passed", () => {
    expect(parseJot("Renew domain 12 oct", sat).dueAt).toBe(endOf(2026, 9, 12));
    expect(parseJot("Renew domain oct 12", sat).dueAt).toBe(endOf(2026, 9, 12));
    expect(parseJot("Book flights 2 jan", sat).dueAt).toBe(endOf(2027, 0, 2));
    expect(parseJot("Odd 31 feb", sat)).toEqual({ title: "Odd 31 feb" });
  });

  it("peels effort and date off the end in either order", () => {
    const a = parseJot("Send the invoice 30m fri", sat);
    const b = parseJot("Send the invoice fri 30m", sat);
    expect(a).toEqual(b);
    expect(a).toMatchObject({ title: "Send the invoice", effortMinutes: 30, dueAt: endOf(2026, 9, 9) });
  });

  it("leaves day words inside a title alone", () => {
    expect(parseJot("Plan Monday stand-up agenda", sat)).toEqual({ title: "Plan Monday stand-up agenda" });
    expect(parseJot("Review 2h video notes", sat)).toEqual({ title: "Review 2h video notes" });
  });

  it("files a #tag under the project it starts", () => {
    const j = parseJot("Logo review 1h #north", sat, projects);
    expect(j).toMatchObject({ title: "Logo review", effortMinutes: 60, project: { id: "p1" } });
    expect(parseJot("#harbor Write findings 2h", sat, projects)).toMatchObject({ title: "Write findings", project: { id: "p2" } });
  });

  it("keeps a #tag that matches no project, and says so", () => {
    const j = parseJot("Fix sink #home", sat, projects);
    expect(j).toMatchObject({ title: "Fix sink #home", unknownTag: "#home" });
    expect(j.project).toBeUndefined();
  });

  it("keeps a line of nothing but tokens as typed", () => {
    expect(parseJot("2h fri", sat)).toEqual({ title: "2h fri" });
  });
});

describe("jotLines", () => {
  it("splits a pasted list and drops its bullets and numbering", () => {
    expect(jotLines("- Call mum\n* Buy stamps\n\n1. Fix sink 30m\n[ ] Pay rent fri\n• Water plants")).toEqual([
      "Call mum",
      "Buy stamps",
      "Fix sink 30m",
      "Pay rent fri",
      "Water plants",
    ]);
  });
});
