---
name: verify
description: Build, launch, and drive SCP (the CLI) to verify a change works end-to-end at its real surface.
---

# Verify SCP

SCP is a JVM multi-module app: a Clikt **CLI** (`apps/cli`) and an **MCP stdio server**
(`apps/mcp-server`) over a SQLite DB (SQLDelight) + Markdown mirrors. The CLI is the
fastest surface to drive; it shares `storage/` + `config.yaml` with the server.

## Build / launch

Always set the JVM temp dir first (NIO selectors die otherwise on this machine):

```powershell
$env:TMP='C:\Users\<you>\.jvmtmp'; $env:TEMP=$env:TMP
.\gradlew.bat :apps:cli:installDist --console=plain -q     # produces apps\cli\build\install\scp\bin\scp.bat
```

Point the CLI at a throwaway workspace via the `scp.home` system property, passed through
`JAVA_OPTS` (the generated start script forwards it). Use a fresh `Get-Random` dir per run —
do NOT `Remove-Item` old ones (the sandbox blocks some path deletions):

```powershell
$bat='.\apps\cli\build\install\scp\bin\scp.bat'
$h=Join-Path $env:TEMP ('scphome_'+(Get-Random)); New-Item -ItemType Directory -Force $h | Out-Null
$env:JAVA_OPTS="-Dscp.home=$h"
& $bat init                                   # creates storage/ + config.yaml + schema
& $bat create-project --name demo --description x
& $bat update --project demo --json entry.json   # payload = an UpdateContextInput JSON
& $bat search <term> --project demo
& $bat summary --project demo
& $bat doctor                                 # WAL/FK/FTS/config health checks
```

An `update` entry payload (`UpdateContextInput`): `{ "projectName","toolName","summary",
"entries":[{"title","content","type":"BUG","tags":[],"priority":4}] }`.

## Encryption at rest

Encryption is on iff `SCP_DB_KEY` is a non-blank env var (empty string = off).

```powershell
$env:SCP_DB_KEY='passphrase'; & $bat init ...    # encrypted DB, no markdown mirror
```

Checks that matter:
- `storage\database\scp.db` first 15 bytes are ciphertext, not `SQLite format 3`.
- `search`/`summary`/`doctor` still work WITH the key (FTS5 unaffected — the headline claim).
- Wrong key / missing key → clean one-line error + exit 1 (no stack trace).
- Under encryption, `storage\markdown` stays empty and `update` returns `markdownPath: ""`.

## Gotchas

- POSIX file permissions (`SecureFiles` 0700/0600) **no-op on Windows** — can't observe the
  actual mode here; only confirm nothing errors. Verify modes on Linux/WSL with `stat -c '%a'`.
- Don't run the tests as verification — drive `scp.bat` and read its output.
