<#
.SYNOPSIS
    Installs SCP (scpx + scp-mcp-server) from a GitHub Release into a fixed per-user
    location and adds it to the user PATH. Safe to re-run: each run is an in-place upgrade.

.PARAMETER Version
    A specific release tag to install, e.g. "v0.2.0". Omit to install the latest release.

.PARAMETER InstallDir
    Install root. Defaults to $env:SCP_INSTALL_DIR or "$env:LOCALAPPDATA\scp".

.EXAMPLE
    irm https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.ps1 | iex

.EXAMPLE
    .\install.ps1 -Version v0.2.0
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [string]$InstallDir = $(if ($env:SCP_INSTALL_DIR) { $env:SCP_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA "scp" })
)

$ErrorActionPreference = "Stop"

$Repo = "Being01Amit/shared-context-protocol"

# Warn rather than abort: installing SCP before a JVM is a legitimate order, and the
# binaries are still valid. Without this the mismatch only surfaces later as an
# UnsupportedClassVersionError on the first run.
function Test-Java {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Warning "No 'java' on PATH. SCP needs JDK 17+ to run."
        return
    }
    $first = (& java -version 2>&1 | Select-Object -First 1 | Out-String)
    if ($first -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 17) {
            Write-Warning "Java $major found, but SCP needs JDK 17+. Install a newer JDK before running scpx."
        }
    }
}

function Get-AssetUrl {
    param([string]$Name)
    if ($Version) {
        $versionNum = $Version.TrimStart("v")
        return "https://github.com/$Repo/releases/download/$Version/$Name-$versionNum.zip"
    }
    return "https://github.com/$Repo/releases/latest/download/$Name.zip"
}

function Install-Component {
    param([string]$Name)

    $zipUrl = Get-AssetUrl -Name $Name
    $shaUrl = "$zipUrl.sha256"

    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
    $zipPath = Join-Path $tmpDir "$Name.zip"
    $shaPath = "$zipPath.sha256"

    Write-Host "Downloading $Name from $zipUrl ..."
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing

    try {
        Invoke-WebRequest -Uri $shaUrl -OutFile $shaPath -UseBasicParsing
        $expected = (Get-Content $shaPath | Select-Object -First 1).Split(" ")[0].Trim()
        $actual = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLower()
        if ($expected -and ($actual -ne $expected.ToLower())) {
            throw "checksum mismatch for $Name (expected $expected, got $actual)"
        }
    } catch {
        # Distinguish "no checksum published" (any download failure — the exact
        # exception type varies between Windows PowerShell 5.1 and PowerShell 7+) from
        # an actual mismatch, which must stay fatal.
        if ($_.Exception.Message -like "checksum mismatch*") {
            throw
        }
        Write-Warning "no checksum found for $Name; skipping verification"
    }

    $extractDir = Join-Path $tmpDir "extracted"
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $inner = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $inner) {
        throw "unexpected archive layout for $Name"
    }

    $target = Join-Path $InstallDir $Name
    if (Test-Path $target) {
        Remove-Item -Path $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Move-Item -Path $inner.FullName -Destination $target

    Remove-Item -Path $tmpDir -Recurse -Force
    Write-Host "Installed $Name to $target"
    return (Join-Path $target "bin")
}

function Add-ToUserPath {
    param([string]$BinDir)

    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $parts = @()
    if ($currentPath) { $parts = $currentPath -split ";" }

    if ($parts -notcontains $BinDir) {
        $newPath = if ($currentPath) { "$currentPath;$BinDir" } else { $BinDir }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "Added $BinDir to your user PATH"
    }

    if ($env:Path -split ";" -notcontains $BinDir) {
        $env:Path = "$env:Path;$BinDir"
    }
}

Test-Java

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

# v0.1.0 installed the CLI as "scp", which shadows the OpenSSH scp shipped in
# System32\OpenSSH. Remove it on upgrade; the leftover PATH entry then resolves to
# nothing, so the system scp works again without rewriting the user's PATH.
$legacy = Join-Path $InstallDir "scp"
if (Test-Path $legacy) {
    Remove-Item -Path $legacy -Recurse -Force
    Write-Host "Removed the legacy 'scp' install, which shadowed OpenSSH's scp. The command is now 'scpx'."
}

$scpBin = Install-Component -Name "scpx"
$mcpBin = Install-Component -Name "scp-mcp-server"

Add-ToUserPath -BinDir $scpBin
Add-ToUserPath -BinDir $mcpBin

Write-Host ""
Write-Host "SCP installed. Open a new terminal, then:"
Write-Host "  scpx init"
Write-Host ""
Write-Host "Register the MCP server with an MCP-compatible client using:"
Write-Host "  $mcpBin\scp-mcp-server.bat"
