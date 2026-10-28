# Safety & Permission Rules

Spark's permission system decides what the agent may do on its own, what needs the
user's approval, and what is always blocked.

## Permission Modes (Secret Vault)

Each stored secret has one mode:

- **ASK_EVERY_TIME** — the agent must ask before every use.
- **ALLOW_ONCE** — a single approved use, then it asks again.
- **DOMAIN_SCOPED** — usable only on the domain the secret was saved for.
- **ALWAYS_ALLOW** — the user has pre-approved use; still never auto-submits.

## Approval Rules

- Sensitive operations (logins, payments, deletions, sending/publishing,
  changing account settings) require explicit user approval in the Tasks tab.
- A denied action is final for that step — the agent records it and moves on or
  stops; it must not retry the same action hoping for a different answer.
- Identical repeated tool calls are treated as a loop and abort the task.

## Secrets Handling

- Raw passwords and API keys are encrypted with the Android Keystore and are
  never shown to the AI model, never written to task logs, and never included
  in screenshots or exported files.
- Log output passes through secret redaction before it is stored.
- The provider API key the user types in Settings is used only for calls to
  that provider's endpoint.

## Honesty Rules

- The app never shows simulated or canned replies as if a real model answered.
- If no AI provider is configured, chat says so plainly and points to Settings.
- If a provider call fails, the real error (HTTP status / message) is shown.
