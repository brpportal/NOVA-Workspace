---
name: facebook-post-links
when_to_use: When the user asks for the latest / recent post links of one or more Facebook profiles or pages — "is profile ke latest posts ke links do", "copy the newest post URLs from these FB pages". Use the trained collector tool instead of browsing by hand.
---

# Facebook Post Links — Trained Task #1

Spark has a trained, deterministic collector for Facebook post links. It
visits a profile, scrolls, and extracts the post permalinks — the same
core also runs without AI from the Tasks screen ("Trained Tasks").

## The tool

`collect_facebook_post_links(profile_url, count, method)`

- ONE profile per call. For several profiles, call it once per profile,
  in the order the user gave them, and combine the results.
- `count` = how many of the latest posts to collect: 1 to 100,
  default 5. The links come back latest-first (page order),
  deduplicated, with Facebook tracking parameters removed.
- `method` (optional) = which extraction methods to use:
  `hybrid` (default — the full M1→M5 ladder below), `m1` (visible
  rendered-page links only), or `m3` (embedded structured-data
  mining only). Use the default unless the user asks for a
  specific method.
- The result is also saved in AI-Tasks/ as a markdown file plus
  results.txt / results.csv / results.json / summary.json artifacts
  (the CSV/JSON record which method found each link) — mention the
  saved files when you present the results.

## How the collector tries (automatic — you do not drive this)

In hybrid mode (the default) the collector escalates through
several methods per profile on its own, in order, and stops as soon
as enough links are found:
M1 the mobile profile (settle, extract, scroll) → M2 the profile's
own Posts section → M3 mining the page's embedded data payloads —
usually the strongest source, merged with M1's links → M4 the
desktop variant of the page → M5 the mbasic variant. The tool result
reports which methods were tried and which one produced the links
("Methods: …"). A locked or unavailable profile stops the ladder at
once; a login pause can only come from M1 — the fallbacks never ask
for a login. In `m1` mode only the rendered-DOM methods run (M1,
M2, M5 — no mining, no desktop variant); in `m3` mode only the
mining loop runs. On the Tasks screen the same modes, a per-profile
limit of 1–100, and duplicate/invalid-line checks are built into
the Trained Tasks card.

## Statuses — report them exactly, never blur them

- OK — all requested links were collected.
- PARTIAL — the profile is fine but fewer posts exist than requested.
  Report the actual count; PARTIAL is not a failure.
- NO_POSTS — the profile loaded and its posts area rendered, but no
  post permalinks were found (after every method was tried).
- PROFILE_LOCKED — the profile is locked/private; posts are not visible.
- PROFILE_UNAVAILABLE — banned, deactivated or not found.
- OPEN_FAILED — the page could not be loaded.
- FB_ERROR — Facebook itself errored (or the browser renderer crashed).
- LOGIN_REQUIRED — Facebook is asking for a login before showing the
  profile, including a logged-out "See more on Facebook" gate/nag.

## The login wall (non-negotiable)

LOGIN_REQUIRED means STOP and hand over to the user: tell them Facebook
needs them to log in (they can use the Browser tab / Take control, or
run the collector from the Tasks screen, where the run pauses as
Needs you and resumes after hand-back). NEVER ask the user for their
Facebook password, never type credentials, and never retry the tool in
a loop hoping the wall goes away.

## Presenting results

- Give a clean numbered list of the links per profile, with the status
  and the real count ("3 of 5 found — PARTIAL").
- NEVER invent, guess, shorten or "repair" a link. If a link is not in
  the tool result, it does not exist for you.
- Known limits: reels are not collected (permalinks only); a profile's
  layout can hide posts the collector cannot see — report the status
  honestly instead of working around it with manual browsing, unless
  the user explicitly asks you to look by hand.
