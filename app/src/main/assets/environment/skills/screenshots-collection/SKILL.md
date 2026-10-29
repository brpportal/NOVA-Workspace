---
name: screenshots-collection
description: Spark's core feature #2 — capture real screenshots of Facebook profiles and their latest posts, or of any URL.
when_to_use: When the user asks for screenshots of Facebook profiles/Pages and their posts, or screenshots of specific web pages/URLs as evidence.
---

# Screenshots Collection (trained task — Spark's core feature #2)

## The two core features

Spark has exactly two core features, and they work as a pair:

1. **Links Collection** (`collect_facebook_post_links`) — FINDS the latest post
   links of Facebook profiles.
2. **Screenshots Collection** (`collect_screenshots`) — CAPTURES the evidence:
   real screenshots of the profile page and of those posts.

Use links collection when the user wants the links themselves. Use screenshots
collection when the user wants visual proof — the profile and post pages as
images. Screenshots collection reuses the links collector's discovery core
internally, so the post screenshots always belong to the same latest posts the
links collector would return.

This is a deterministic, pre-trained workflow. Call the tool; do NOT try to
take these screenshots by browsing manually (navigate / take_screenshot step by
step) — that is slower, inconsistent, and misses the run artifacts.

## The tool

`collect_screenshots(urls, posts_per_profile, method)`

- `urls` (required): a list of URLs. Each line is classified automatically:
  - A **Facebook profile/Page URL** → profile mode: the profile page is
    screenshotted, its latest posts are discovered with the links collector's
    machinery, and each discovered post page is screenshotted too.
  - **Any other http(s) URL** (a direct post, article, or page on any site) →
    direct mode: one screenshot of that page.
- `posts_per_profile` (optional, default 2, allowed 1-10): how many post
  screenshots to take per Facebook profile. The profile screenshot itself is
  always taken in addition, so the default captures 3 images per profile
  (1 profile + 2 posts).
- `method` (optional): how the latest posts are discovered — `hybrid`
  (default; the full M1-M5 ladder), `m1` (visible rendered-DOM links only),
  or `m3` (page-source structured-data mining only). It changes discovery
  only, never the capture.

Call it ONCE with the whole list when the user gives several URLs — the tool
visits them in order and always works through the full list, exactly like the
Tasks-screen collector. One bad source never stops the rest.

## What it saves

Every run writes to `AI-Tasks/screenshots-<timestamp>/`:

- Per Facebook profile: a subfolder `link-NN-<name>/` containing `profile.png`,
  `post-1.png`, `post-2.png`, … (one per discovered post, up to the count).
- Per direct URL: `link-NN-<name>.png` in the run folder.
- `index.md` — per source: its label, URL, status, and the screenshot files
  with the post URLs they belong to.
- `summary.json` — the run config, per-source statuses and shot counts, and
  totals (sources, shots taken, shots missed).
- If a single PNG exceeds about 6 MB, a JPEG copy (quality 85) is saved next
  to it and reported too.

All of these files are registered under Files → Created by Spark. When you
report results, quote the EXACT file paths the tool returned — never invent
or guess a path.

## Statuses (report them exactly, never soften or invent)

- `OK` — every requested screenshot was captured.
- `PARTIAL` — some screenshots captured, some missed (say how many of how
  many).
- `NO_POSTS` — the profile opened and its profile screenshot was captured,
  but no post links exist or none could be found. The profile shot still
  counts; the post shots are what is missing.
- `PROFILE_LOCKED` — the profile demands friendship/login to view (the
  notice page itself is captured as the profile screenshot).
- `PROFILE_UNAVAILABLE` — the profile does not exist or was removed (the
  notice page itself is captured as the profile screenshot).
- `OPEN_FAILED` — the page could not be opened or the capture failed.
- `FB_ERROR` — Facebook returned its own error page (captured as evidence).
- `LOGIN_REQUIRED` — Facebook is showing a login wall.

## The login wall rule (non-negotiable)

If any source comes back `LOGIN_REQUIRED`:

- Tell the user plainly: Facebook needs THEM to log in (Browser tab →
  log in → hand control back), then the capture can be re-run.
- NEVER ask for, accept, or type a Facebook username/password. Spark's
  browser holds the user's own session; credentials are never handled by
  the agent.
- Do NOT retry `collect_screenshots` in a loop hoping the wall goes away.
  One clear hand-off to the user, then stop.

## Presenting results

- Give the run folder path first, then per source: label, status, and its
  screenshot files (with the post URL each post screenshot belongs to).
- Quote statuses exactly. A `PARTIAL` is not "done"; a `NO_POSTS` profile is
  not an error — say what was actually captured and what is missing.
- If the user wants only the links (no images), use `collect_facebook_post_links`
  instead; if they want the images as well, this tool already includes the
  discovery — do not run both for the same profile.

## Known limits (state them honestly when relevant)

- Screenshots show the top of each page as rendered in Spark's browser,
  after the page settles — not a full-page stitched capture.
- Post discovery has the same limits as links collection: it finds the
  latest posts of public profiles/Pages visible to the signed-in session.
- Video posts are captured as their post page, not as playable video.
