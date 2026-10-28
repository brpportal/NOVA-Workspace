---
name: phone-agent-playbook
when_to_use: For any multi-step task on this phone — the observe, plan, act, verify loop for driving the in-app browser and workspace tools.
---

# Phone Agent Playbook

How Spark runs a task end-to-end on the device. One loop, repeated until the
goal is met or an honest stop condition is hit.

## The Loop: Observe → Plan → Act → Verify

1. **Observe.** Start from facts, not assumptions.
   - Browser work: read the current URL and page title, then `get_page_state`.
     For interactive pages call `get_set_of_marks` and use the numbered labels.
   - File work: `file_list` the target folder before reading or writing.
2. **Plan.** State the next single action and what result would prove it
   worked. Keep the plan short enough to fit the remaining step budget.
3. **Act.** Execute exactly one meaningful action per step: `navigate`,
   `click`, `type`, `scroll`, `file_write`, `file_append`, and so on.
   Prefer labelled elements over raw selectors; prefer creating a file over
   overwriting one.
4. **Verify.** Re-read the page state, re-list the folder, or take a
   screenshot (`take_screenshot`) after significant transitions. If the
   expected change did not happen, say so in the step result — never report
   success you did not observe.

## Evidence

- Screenshots taken with `take_screenshot` are stored as task evidence and
  shown in the task detail as "Browser screenshot i of N". Take one after
  each major page transition, not after every micro-step.
- Pages you visit are recorded as sources for the task (title + URL). The
  final summary should let the user trace every claim back to a visited page.

## Stop Conditions

Stop and report honestly instead of pushing on when:

- A CAPTCHA, 2FA prompt, or login form appears — ask the user to take
  control ("Take control" in the Browser tab) and pause.
- The same action would be repeated identically — that is a loop, not
  progress.
- A tool returns an error twice for the same step — change approach or stop.
- The step budget is nearly exhausted — summarize what is done and what is
  left rather than rushing unverified actions.

## Records

- Every run writes `task.md` and `log.txt` under `AI-Tasks/<date>_<title>/`
  and appends a row to `AI-Tasks/index.md`.
- Files the agent creates are listed under "Created by Spark" in the Files
  tab; put deliverables in `Documents/`, `Research/`, or `Exports/` as the
  task requires.
