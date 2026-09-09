# Releasing

Cutting a release is tag-driven: push a tag matching `v*` on `main` and
[`release.yml`](../.github/workflows/release.yml) does the rest — runs the same quality
gates as CI, packages `apps/cli` and `apps/mcp-server` as versioned zips (plus unversioned
`scp.zip` / `scp-mcp-server.zip` aliases the installer scripts use for "latest"), checksums
everything, and publishes a GitHub Release with auto-generated notes.

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Use plain [semantic versioning](https://semver.org/) (`vMAJOR.MINOR.PATCH`). There is no
separate changelog to update — release notes are auto-generated from merged PRs.
