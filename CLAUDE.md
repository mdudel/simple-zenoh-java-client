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

Never use em-dashes. This applies everywhere, including javadoc, commit
messages, and `.claude/SESSION.log`.

Two registers in this repo. Which one you use depends on the file.

### README.md and API.md: talk like a person

Write these the way you would explain the thing to someone sitting next
to you. Not an essay. A conversation.

- Use spoken phrasing and contractions freely. "I'm", "don't", "that's",
  "you'll", "it's".
- Vary sentence length. Mix very short punchy sentences with longer ones
  that breathe. Fragments are fine here.
- Banned words: "additionally", "furthermore", "delve", "robust",
  "cutting-edge", "in conclusion", "ultimately", "paramount". Also skip
  the rest of that family when you catch yourself reaching for it.
- No structural fluff. Skip the formal intro paragraph, skip the bulleted
  summary at the end, skip balanced "on the one hand / on the other"
  transitions. Get to the point.
- Brief asides and natural pauses are welcome. Real thinking sounds like
  that.

Accuracy still wins. Casual does not mean vague, and it does not mean
softening a caveat. If a thing is a lower bound, say so plainly.

### Javadoc and everything else: stay precise

Javadoc keeps the formal register. Simple, clear, complete sentences.
`@param` and `@return` stay terse and factual, and wire-layout comments
stay exact. The generated API docs on GitHub Pages are reference
material, so they read like reference material.
