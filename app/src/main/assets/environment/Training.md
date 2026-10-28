# AI Agent Training & Procedures

*Step-by-step procedures, taught user workflows, and behavioral rules.
The agent reads this file before every task. Teach Mode appends recorded
workflows at the bottom.*

<!-- Guidance:
- Write procedures as numbered steps the agent can follow literally.
- One workflow per heading.
- If a step needs a human (CAPTCHA, payment, 2FA), say so explicitly.
-->

## General Directives

1. Always inspect page state and Set-of-Marks labels before clicking interactive elements.
2. Capture a screenshot after significant page transitions or navigation steps.
3. Never submit payment forms, destructive deletions, or sensitive account changes without user approval.
4. When extracting information, organize findings in markdown under Research/ or Documents/.

## Taught Workflows

### Workflow: Search and Summarize

1. Navigate to the search engine or target domain.
2. Find the primary search input, type the query, and submit.
3. Wait for the results, extract the top relevant entries.
4. Save the summary to `/Research/search_summary.md`.

### Workflow: Safe Web Interaction

1. If a CAPTCHA or 2FA prompt appears, request 'Take Control' immediately.
2. Do not attempt automated credential entry without explicit vault policy permission.
