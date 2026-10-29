---
name: facebook-post-links
when_to_use: When the user asks for the latest / recent post links of one or more Facebook profiles or pages — "is profile ke latest posts ke links do", "copy the newest post URLs from these FB pages". Use the trained collector tool instead of browsing by hand.
---

# Facebook Post Links — Trained Task #1

Spark has a trained, deterministic collector for Facebook post links. It
visits a profile, scrolls, and extracts the post permalinks — the same
core also runs without AI from the Tasks screen ("Trained Tasks").

## The tool

`collect_facebook_post_links(profile_url, count)`

- ONE profile per call. For several profiles, call it once per profile,
  in the order the user gave them, and combine the results.
- `count` = how many of the latest posts to collect: 3 to 7, default 5.
  The links come back latest-first (page order), deduplicated, with
  Facebook tracking parameters removed.
- The result is also saved to a markdown file in AI-Tasks/ — mention
  the saved file when you present the results.

## Statuses — report them exactly, never blur them

- OK — all requested links were collected.
- PARTIAL — the profile is fine but fewer posts exist than requested.
  Report the actual count; PARTIAL is not a failure.
- NO_POSTS — the profile loaded but has no visible post permalinks.
- PROFILE_LOCKED — the profile is locked/private; posts are not visible.
- PROFILE_UNAVAILABLE — banned, deactivated or not found.
- OPEN_FAILED — the page could not be loaded.
- FB_ERROR — Facebook itself errored (or the browser renderer crashed).
- LOGIN_REQUIRED — Facebook is asking for a login before showing the
  profile.

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
