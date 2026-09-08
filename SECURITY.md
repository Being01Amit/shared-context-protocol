# Security

SCP is a local-first shared-memory layer for AI coding agents: everything runs on your own
machine, there's no network service, and the process boundary is real (anything that can run
`scp-mcp-server` can already read `scp.db` directly). Most conventional server-side security
controls don't apply here, and intentionally aren't built.

The risk that *is* real, and specific to what this project does, is described below. Read it
before relying on SCP across agents or tools you don't fully trust.

## Known limitation: prompt injection / memory poisoning

**Status: partially mitigated, not fully resolved.**

Content one agent writes into shared context (`update_context`) is later returned to a
*different* agent's prompt context via `hydrate_context`, `search_context`, `timeline`, and
`project_summary`. If an agent is asked to summarize something it read from an untrusted source
— a GitHub issue, a dependency's README, a fetched web page — and that source contains embedded
instructions, nothing stops the agent from recording it as project context. A later agent then
receives that text as if it were the project's own decisions.

Two things make this worse than ordinary prompt injection:

- **It's persistent and cross-agent.** A normal injected instruction is bounded by one session.
  Here, a poisoned entry stays in the database and reaches every future agent — currently there
  is no way to delete or supersede an entry once written.
- **It can be ranked into prominence.** The type and priority of a stored entry are caller-
  controlled, so a poisoned entry written as `type: DECISION, priority: 5` is promoted toward the
  top of what a resuming agent sees first.

**What's implemented today:** every read-path response carries an explicit notice — at the top
of the payload, and again on the specific `resumePoint.whereWeStopped` field an agent is told to
act on — stating that the content is stored data written by previous agent sessions, not an
instruction to follow. Secret-shaped values (API keys, tokens, etc.) are also redacted before
storage. Both MCP tool descriptions and the returned payloads carry this framing.

**What's not implemented yet:** there is no provenance or trust field on stored entries, no
way to rank untrusted content lower than verified content, and no cap on how much priority a
caller-supplied entry can claim. The fencing above gives a consuming agent a signal to be
skeptical — it does not stop a poisoned entry from being stored, kept forever, or ranked
prominently in the first place.

Full technical detail, including the concrete attack chain and the recommended fixes in
dependency order, is in [`docs/review/12-security-assessment.md`](docs/review/12-security-assessment.md).

**Practical guidance until this is fully resolved:** be cautious about letting an agent write
untrusted, externally-sourced content into shared context, and treat anything hydrated from SCP
as informational rather than as ground truth, especially in a multi-agent setup.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting for this repository (**Security** tab →
**Report a vulnerability**) rather than opening a public issue, so any real, exploitable
finding can be assessed before it's public. For anything that isn't sensitive — a hardening
suggestion, a question about the threat model — a regular issue is fine.
