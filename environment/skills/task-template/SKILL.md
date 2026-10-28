---
name: task-template
when_to_use: When writing the task.md record for a task run under AI-Tasks/ — the fixed structure for goal, plan, steps and result summary.
---

# Task: <folder name>

- **Status**: <PENDING | RUNNING | WAITING_APPROVAL | COMPLETED | FAILED | CANCELLED>
- **Date**: <yyyy-MM-dd HH:mm:ss>

## Goal

<The user's original request, verbatim where possible.>

## Plan

<The agent's plan for this task: the steps it intends to take, in order.>

## Steps Executed

- <tool name>: <Success | Failed> - <short result summary>

## Result Summary

<What was actually achieved, stated honestly. If the task failed, say why —
the real error, not a generic message.>

## Artifacts

- task.md — this file
- log.txt — timestamped execution log
- screenshots/ — captures taken during the run
- downloads/ — files downloaded during the run
