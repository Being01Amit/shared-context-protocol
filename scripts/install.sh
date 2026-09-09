#!/usr/bin/env sh
# Installs SCP (scpx + scp-mcp-server) from a GitHub Release into a fixed per-user
# location and adds it to PATH. Safe to re-run: each run is an in-place upgrade.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.sh | sh
#   ./install.sh v0.2.0           # pin a specific release; omit for latest
#
# Env overrides:
#   SCP_INSTALL_DIR   install root (default: $HOME/.scp)

set -eu

REPO="Being01Amit/shared-context-protocol"
VERSION="${1:-}"
INSTALL_DIR="${SCP_INSTALL_DIR:-$HOME/.scp}"

log() { printf '%s\n' "$*"; }
die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# Warn rather than abort: installing SCP before a JVM is a legitimate order, and the
# binaries are still valid. Without this the mismatch only surfaces later as an
# UnsupportedClassVersionError on the first run.
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        log "warning: no 'java' on PATH. SCP needs JDK 17+ to run."
        return 0
    fi
    major=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    case "$major" in
        ''|*[!0-9]*) return 0 ;; # unrecognized format, don't guess
    esac
    if [ "$major" -lt 17 ]; then
        log "warning: Java $major found, but SCP needs JDK 17+. Install a newer JDK before running scpx."
    fi
}
check_java

if [ -n "$VERSION" ]; then
    BASE_URL="https://github.com/$REPO/releases/download/$VERSION"
    VERSION_NUM="${VERSION#v}"
    asset_name() { printf '%s-%s.zip' "$1" "$VERSION_NUM"; }
else
    BASE_URL="https://github.com/$REPO/releases/latest/download"
    asset_name() { printf '%s.zip' "$1"; }
fi

fetch() {
    # fetch <url> <dest-file>
    curl -fsSL "$1" -o "$2"
}

verify_checksum() {
    # verify_checksum <archive> <checksum-file>
    # Compare the hash directly instead of `sha256sum -c`, which also matches the filename
    # recorded inside the sidecar: the "latest" alias downloads as scpx.zip while the
    # sidecar names the versioned scpx-<version>.zip it was generated from.
    expected=$(cut -d' ' -f1 <"$2")
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$1" | cut -d' ' -f1)
    elif command -v shasum >/dev/null 2>&1; then
        actual=$(shasum -a 256 "$1" | cut -d' ' -f1)
    else
        die "neither sha256sum nor shasum is available to verify the download"
    fi
    [ -n "$expected" ] || die "empty checksum file for $1"
    [ "$expected" = "$actual" ] || die "checksum mismatch for $1 (expected $expected, got $actual)"
}

install_component() {
    # install_component <name>   (e.g. "scpx" or "scp-mcp-server")
    name="$1"
    zip_name=$(asset_name "$name")
    tmp_dir=$(mktemp -d)
    archive="$tmp_dir/$zip_name"

    log "Downloading $zip_name..."
    fetch "$BASE_URL/$zip_name" "$archive"
    fetch "$BASE_URL/$zip_name.sha256" "$archive.sha256" \
        || log "warning: no checksum found for $zip_name; skipping verification"

    if [ -f "$archive.sha256" ]; then
        verify_checksum "$archive" "$archive.sha256"
    fi

    target="$INSTALL_DIR/$name"
    rm -rf "$target"
    mkdir -p "$target" "$tmp_dir/extracted"
    unzip -q "$archive" -d "$tmp_dir/extracted"
    # The zip contains one top-level dir (e.g. scpx-1.0.0/); flatten it into $target.
    inner=$(find "$tmp_dir/extracted" -mindepth 1 -maxdepth 1 -type d | head -1)
    [ -n "$inner" ] || die "unexpected archive layout for $zip_name"
    mv "$inner"/* "$target"/
    chmod +x "$target"/bin/* 2>/dev/null || true

    rm -rf "$tmp_dir"
    log "Installed $name to $target"
}

mkdir -p "$INSTALL_DIR"

# v0.1.0 installed the CLI as "scp", which shadows OpenSSH's scp for anyone with it on
# PATH. Remove it on upgrade; the leftover PATH entry then resolves to nothing, so the
# system scp works again without editing the user's shell profile.
if [ -d "$INSTALL_DIR/scp" ]; then
    rm -rf "$INSTALL_DIR/scp"
    log "Removed the legacy 'scp' install, which shadowed OpenSSH's scp. The command is now 'scpx'."
fi

install_component "scpx"
install_component "scp-mcp-server"

add_to_path() {
    # add_to_path <bin-dir>
    bin_dir="$1"
    marker="# added by SCP installer"
    for profile in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
        [ -f "$profile" ] || continue
        if ! grep -qF "$marker ($bin_dir)" "$profile" 2>/dev/null; then
            {
                printf '\n%s (%s)\n' "$marker" "$bin_dir"
                printf 'export PATH="%s:$PATH"\n' "$bin_dir"
            } >>"$profile"
            log "Added $bin_dir to PATH in $profile"
        fi
        return 0
    done
    log "No shell profile found; add this to your shell config manually:"
    log "  export PATH=\"$bin_dir:\$PATH\""
}

add_to_path "$INSTALL_DIR/scpx/bin"
add_to_path "$INSTALL_DIR/scp-mcp-server/bin"

log ""
log "SCP installed. Open a new shell (or 'source' your profile), then:"
log "  scpx init"
log ""
log "Register the MCP server with an MCP-compatible client using:"
log "  $INSTALL_DIR/scp-mcp-server/bin/scp-mcp-server"
