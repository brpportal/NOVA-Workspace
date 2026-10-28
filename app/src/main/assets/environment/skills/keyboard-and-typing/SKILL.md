---
name: keyboard-and-typing
when_to_use: Whenever you type into a web page — search boxes, login and signup forms, multi-field forms, editors. Covers focus, committing text so forms register it, Enter/Tab behaviour, autocomplete, and credential caution.
---

# Keyboard and Typing

Typing is where web automation quietly fails: text that "was typed" but
the form never saw. These rules make typed text real.

## How typing works here

`type_text(index, text, clear_first, submit)` does four things in order:

1. **Focuses the field** (scroll into view, then focus). Unfocused typing
   goes nowhere — never assume a field is focused because it looks
   selected.
2. **Sets the value with the native setter**, the same way a real
   keyboard-driven edit registers, then fires `input` and `change`
   events. This is why the text "sticks" in React and other
   framework-controlled forms that ignore direct value assignment.
3. Optionally **clears first** (`clear_first=true`, the default): stale
   text, placeholder-like prefilled values, and previous entries are
   replaced, not appended to. Use `clear_first=false` only when you
   deliberately append (e.g. adding a line to a note field).
4. Optionally **submits** (`submit=true`): Enter key events plus the
   form's real submit. Right for search boxes and single-field forms;
   wrong for multi-field forms — fill everything first, then click the
   submit control by index.

## Verify the value stuck

After typing, the next observation echoes each field's current value.
**Read it.** If the value is missing, truncated, or shows something else
(autocomplete replaced it, the page re-rendered, a mask reformatted it):

- Re-observe to get fresh indices, retype with `clear_first=true`.
- If a mask reformats (dates, phone numbers, card fields), type the raw
  digits and let the mask format them; verify the formatted result.
- Two failed attempts on the same field = change approach or report;
  never hammer a field.

## Search boxes

- Pattern: observe → find the search field (type `search`, or named
  `q` / `query` / `s`) → `type_text` with `submit=true` → verify the
  results page (title/URL changed, results in the excerpt).
- **Autocomplete dropdowns:** if you type without submitting, a
  suggestion list may appear as new elements in the next observation.
  Clicking a suggestion by index is valid — but only when it matches
  the intended query; otherwise submit the typed text itself.
- On results pages the search box keeps your query — a second search is
  `clear_first=true` and a fresh `type_text` on the new observation's
  index for that field.

## Multi-field forms

- Fill in **visual order** (top to bottom as observed), verifying each
  value in the following observation before moving on.
- **Tab order is the page's, not yours:** there is no Tab key tool —
  moving to "the next field" means acting on the next field's index.
- Editors and contentEditable areas behave like text fields for
  `type_text`, but verify by excerpt, since their value is their text.
- Selects, checkboxes, radios: not typing targets — click by index and
  verify the resulting state.
- Submit **once**, at the end, after one full verification pass over
  every field (see browser-mastery's forms walkthrough).

## Special keys — what exists and what does not

- **Enter** = `submit=true` on `type_text`. That is the only key with a
  dedicated route.
- **Escape** has no tool: to close a dialog or dropdown, click its
  close (×) element or the element that opened it.
- Never try to send key chords or shortcuts; drive the UI by indices.

## Credential caution (non-negotiable)

- Passwords and vault secrets are **never typed by you**. Login forms
  are filled only by `vault_autofill`, which injects the secret for the
  matching domain without showing it to you — or by the user after
  Take control.
- Observations never echo password values ("(hidden)") — if a password
  ever appears visible in a page or result, do not repeat it in your
  summary or step log.
- Before any autofill, check the domain in the current URL is the real
  site the secret belongs to; lookalike domains mean stop and report.
- Do not type one site's credentials into another site's form, even if
  the user pasted them in chat — that is a "Needs you" stop: explain
  and let the user take over.
