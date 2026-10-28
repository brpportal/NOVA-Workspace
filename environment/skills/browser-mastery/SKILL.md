---
name: browser-mastery
when_to_use: For ANY work in the in-app browser — searching, reading pages, filling forms, following links. This is the complete browser-work manual; read it before browser tasks.
---

# Browser Mastery — the complete browser-work manual

You work in a real WebView on the user's phone. You never see the screen
directly: you perceive pages through observations and screenshots, and you
act through tools. This manual is the whole method.

## 1. Anatomy of the browser in this app

- **The WebView** is the page itself. One tab. Its viewport is what
  screenshots show and what coordinates refer to (see browser-cursor-control).
- **The address bar** shows the current URL with an "On this phone" label:
  everything runs on the device, there is no cloud browser.
- **The control banner** above the page states who is driving:
  "AI IS CONTROLLING" while you work (the user can press **Take control**
  at any moment — if that happens your task pauses; never fight it),
  "You're in control" when the user holds the browser (**Hand back to the
  agent** returns it to you), "Needs you" when you are stopped on an
  approval or a manual step.
- **The Agent Plan chip + View Log** shows your current step and recent
  actions to the user. Your step log is visible — keep actions explainable.

## 2. Your senses and hands (the toolset)

- `navigate(url)` — load a URL. `browser_back`, `browser_forward`,
  `browser_reload`, `wait_for_load(timeout_ms)` — history and loading.
- `observe_page` — your eyes: URL, title, the numbered interactive-element
  list (set-of-marks), and a text excerpt. After navigation and every
  page-changing action, the tool result already contains a fresh
  observation; otherwise call it yourself.
- `click_element(index)` / `type_text(index, text, clear_first, submit)` —
  your hands, by observation index. Indices die when the page changes.
- `tap(x, y)` — coordinate fallback for canvas/image-only areas only.
- `scroll(dx, dy)` — reveal more of the page, then observe again.
- `get_visible_text` — long-form reading of the current page text.
- `find_element(selector)` — verify a known selector exists (rarely needed;
  indices are the normal route).
- `take_screenshot` — visual evidence; stored on the task and shown to
  the user. Take one after major transitions, not after every micro-step.
- `get_set_of_marks` — overlay the numbers on the page itself when a
  screenshot must show which element is which.

## 3. The loop (always)

**Observe → Plan → Act → Verify.** One meaningful action per step. State
(in your step result) what you expect to happen, then check the next
observation for exactly that: URL changed? Field value present? Results
listed? If the expected change is absent, the action failed — say so and
change approach. Never report success you did not observe.

## 4. Navigation strategies

- Know the exact URL? Go straight there with `navigate`.
- Don't know it? Search (section 5) or navigate to the site's home page
  and follow observed links by index.
- After `navigate`, the attached observation tells you where you landed —
  redirects, interstitials, and cookie walls are normal first sights.
- Keep track of where you are: cite the URL of every page your answer
  relies on (visited pages are recorded as the task's sources).

## 5. Search strategies

Two routes, both reliable:

1. **Search box route:** navigate to the search engine, observe, find the
   search field (an `<input type="search">` or a `<textarea>`/input named
   `q`), `type_text` with `submit=true`. Verify the results page loaded.
2. **Direct URL route:** navigate to the results URL directly, e.g.
   `https://www.google.com/search?q=Pakistan` — fewer steps, same result.

Craft queries like a researcher: specific nouns, site names, and dates
beat long questions. Read results from the observation's text excerpt and
element list; open the promising result by clicking its index; use
`browser_back` to return to the results and open the next one. If results
are thin, reformulate the query instead of scrolling forever.

**Worked example — "search Pakistan on Google":**
1. `navigate` to `https://www.google.com`. Observation shows the search
   field (input named `q`, labelled "Search").
2. `type_text` on that index, text `Pakistan`, `submit=true`.
   (Or skip both steps: `navigate` to
   `https://www.google.com/search?q=Pakistan`.)
3. New observation: title contains "Pakistan", the text excerpt lists
   results. The search worked — verified, not assumed.
4. Open a result with `click_element` if the task needs detail, then
   finish with a summary that cites the visited pages.

## 6. Reading pages

- Short pages: the observation's excerpt is enough.
- Long articles: `get_visible_text` for the full text; scroll + observe
  to walk down a feed or results list.
- Tables and dense layouts read badly as raw text — a screenshot plus
  the element list is often clearer.
- Extract facts, don't dump pages: the final summary answers the user's
  question and cites sources.

## 7. Forms end-to-end

1. Observe. Identify every field and the submit control by index.
2. Fill fields in visual order with `type_text` (see
   keyboard-and-typing). After each field, the next observation shows the
   field's current value — verify it stuck before moving on.
3. Selects and checkboxes are elements too: click them by index and
   verify the resulting state.
4. Before submitting, re-check every value once (one last observation).
5. Click submit by index (or `submit=true` on the last field when the
   form is a simple search-style form).
6. Verify the outcome page: success message, new URL, confirmation
   number. No confirmation observed = not submitted — investigate, do
   not resubmit blindly (double submissions are a real harm).

**Worked example — a contact form:** observe → type name (verify) →
type email (verify — watch for autocomplete suggestions stealing the
value) → type message → observe once fully → click Send → observe the
"Message sent" confirmation → screenshot as evidence → summarise.

## 8. Popups, cookie banners, login walls

- **Cookie/consent banners:** find the dismiss/accept button by label
  ("Accept", "I agree", "Reject all", the ×) and click it by index, then
  re-observe. Prefer the least-committal option that unblocks the page.
- **Popups/dialogs:** they are page elements — observe, read, act by
  index. Escape is not a tool; click the close control instead.
- **Login walls:** STOP. Credentials are the user's. Ask them to press
  Take control and log in themselves, or use `vault_autofill` when a
  vault secret exists for this exact domain. Never type a password you
  were given in chat into a page, and never try to bypass a CAPTCHA,
  2FA, or bot check — that is a "Needs you" stop (section 10).

## 9. Waiting and loading

Pages lie about being ready. After heavy transitions call
`wait_for_load`; if an observation shows an empty element list or a
spinner's page, wait and observe again before concluding anything. A
blank observation usually means "still loading", not "empty page".

## 10. Error recovery table

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Element N not found" | Page changed since the observation | `observe_page` for fresh indices; never reuse stale ones |
| Click had no effect | Overlay/banner intercepted, or element needs scroll | Scroll, re-observe, click once more; if still nothing, screenshot and report |
| Typed text missing | Field re-rendered or autocomplete replaced it | Re-observe; retype with `clear_first=true`; verify the value before continuing |
| Page blank / no elements | Still loading, or JS-heavy app shell | `wait_for_load`, observe again; reload once |
| Same result twice | You are in a loop | Change approach — different route, query, or tool. A third identical attempt is forbidden |
| Redirect loop / error page | Site or network problem | Stop and report the exact page state |
| Approval requested | The action is sensitive by policy | Wait for the user; the task resumes when they approve |

## 11. When to stop and ask the user ("Needs you")

Stop and hand over instead of pushing on when: a CAPTCHA/2FA/bot check
appears; a login is required and no vault secret matches the domain; a
payment or purchase must be confirmed; the page asks for personal data
you were not given; or the same step has failed twice with different
approaches. Stopping honestly is a success state — a wrong action is not.

## 12. Budget and honesty

You have a limited step budget. Spend steps on verification, not on
re-trying guesses. Every claim in your final summary must trace to a
page you actually visited (the task's Sources list proves it). If the
task could not be completed, say exactly where it stopped and what the
user can do — that is a finished task too.
