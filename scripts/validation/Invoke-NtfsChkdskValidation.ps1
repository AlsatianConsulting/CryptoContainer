param(
    [Parameter(Mandatory = $true)]
    [string]$ContainerPath,

    [Parameter(Mandatory = $true)]
    [string]$Password,

    [string]$VeraCryptPath = "$env:ProgramFiles\VeraCrypt\VeraCrypt.exe",

    [ValidatePattern('^[A-Z]$')]
    [string]$DriveLetter = "V",

    [switch]$ReadOnly = $true,

    [switch]$SkipMount,

    [string]$OutputJson = "",

    [string]$ChkdskMode = "/scan"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ContainerPath)) {
    throw "ContainerPath not found: $ContainerPath"
}

if ([string]::IsNullOrWhiteSpace($OutputJson)) {
    $OutputJson = Join-Path -Path (Split-Path -Parent $ContainerPath) -ChildPath "chkdsk_result.json"
}

if (-not $SkipMount.IsPresent) {
    if (-not (Test-Path -LiteralPath $VeraCryptPath)) {
        throw "VeraCrypt executable not found: $VeraCryptPath"
    }
}

$targetDrive = "$DriveLetter`:"
$mountedHere = $false
$mountExit = 0
$dismountExit = 0
$startUtc = [DateTime]::UtcNow.ToString("o")
$chkdskText = ""

try {
    if (-not $SkipMount.IsPresent) {
        $mountArgs = @("/q", "/s", "/v", $ContainerPath, "/l", $DriveLetter, "/p", $Password)
        if ($ReadOnly.IsPresent) {
            $mountArgs += @("/m", "ro")
        }
        & $VeraCryptPath @mountArgs | Out-Null
        $mountExit = $LASTEXITCODE
        if ($mountExit -ne 0) {
            throw "VeraCrypt mount failed with code $mountExit"
        }
        $mountedHere = $true
    }

    $chkdskOutput = & chkdsk.exe $targetDrive $ChkdskMode 2>&1
    $chkdskText = ($chkdskOutput | Out-String)
    $chkdskExit = $LASTEXITCODE

    $noProblems = $chkdskText -match "found no problems"
    $corrected = $chkdskText -match "made corrections"
    $dirty = $chkdskText -match "errors in the file system"

    $result = [ordered]@{
        status = if ($chkdskExit -eq 0 -and $noProblems) { "PASS" } elseif ($chkdskExit -eq 0 -and $corrected) { "WARN_FIXED" } else { "FAIL" }
        startedAtUtc = $startUtc
        finishedAtUtc = [DateTime]::UtcNow.ToString("o")
        containerPath = $ContainerPath
        driveLetter = $DriveLetter
        chkdskMode = $ChkdskMode
        mountExitCode = $mountExit
        chkdskExitCode = $chkdskExit
        noProblems = $noProblems
        corrected = $corrected
        dirtyDetected = $dirty
        output = $chkdskText.Trim()
    }

    $json = $result | ConvertTo-Json -Depth 8
    Set-Content -LiteralPath $OutputJson -Value $json -Encoding utf8

    if ($result.status -eq "FAIL") {
        Write-Error "NTFS integrity verification failed. See $OutputJson"
    } elseif ($result.status -eq "WARN_FIXED") {
        Write-Warning "CHKDSK reported corrections. See $OutputJson"
    } else {
        Write-Host "NTFS integrity verification passed. Output: $OutputJson"
    }
}
finally {
    if ($mountedHere) {
        try {
            & $VeraCryptPath /q /s /d $DriveLetter | Out-Null
            $dismountExit = $LASTEXITCODE
            if ($dismountExit -ne 0) {
                Write-Warning "VeraCrypt dismount exit code: $dismountExit"
            }
        }
        catch {
            Write-Warning "VeraCrypt dismount failed: $($_.Exception.Message)"
        }
    }
}
