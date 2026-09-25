# Project notes for Claude Code

## Cross-machine session continuity

This project is worked on from multiple laptops. `.claude/SESSION.log` is
committed to git (not ignored) specifically so session context survives
switching machines.

- At the **start** of a session, read `.claude/SESSION.log` to see what was
  last worked on and what's pending.
- At the **end** of a session (or after a meaningful chunk of work), append a
  new dated entry to `.claude/SESSION.log` summarizing what changed, any
  decisions made, and what's next. Add entries at the top of the log.
- This log is separate from git history — use it for intent/context/next
  steps, not a duplicate of commit messages.
- If the user wants the log carried to the other laptop, remind them to
  commit and push/pull `.claude/SESSION.log` (it's just a normal tracked file).

## Google auth troubleshooting

If Google auth stops working, reauthenticate by running:

```
gcloud auth login
gcloud auth application-default login
```

## Documentation style

When writing documentation, do not use em-dashes. Write in simple, clear,
and complete sentences.
