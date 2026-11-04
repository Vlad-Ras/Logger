# Starts a bounded JFR recording on a local Java 21 process; does not stop Minecraft.
# Run in Windows PowerShell 5.1+ after finding the PID with: jcmd -l
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$JavaProcessId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$Label,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [ValidateRange(10, 3600)]
    [int]$Seconds = 120
)

$ErrorActionPreference = 'Stop'
$jcmd = Get-Command 'jcmd.exe' -ErrorAction SilentlyContinue
if (-not $jcmd) { $jcmd = Get-Command 'jcmd' -ErrorAction SilentlyContinue }
if (-not $jcmd) { throw 'Java 21 JDK jcmd not found in PATH. Install a Java 21 JDK and reopen PowerShell.' }
$jcmdPath = $jcmd.Source

$folder = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($folder) | Out-Null
$recording = [System.IO.Path]::Combine($folder, "$Label.jfr")
$metadata = [System.IO.Path]::Combine($folder, "$Label-jvm.txt")
if ([System.IO.File]::Exists($recording) -or [System.IO.File]::Exists($metadata)) {
    throw "Existing output for $Label. Use a new label to avoid overwriting evidence."
}

$version = & $jcmdPath $JavaProcessId 'VM.version' 2>&1
if ($LASTEXITCODE -ne 0) { throw "jcmd VM.version failed: $version" }
$flags = & $jcmdPath $JavaProcessId 'VM.flags' 2>&1
if ($LASTEXITCODE -ne 0) { throw "jcmd VM.flags failed: $flags" }
$version | Set-Content -LiteralPath $metadata -Encoding UTF8
$flags | Add-Content -LiteralPath $metadata -Encoding UTF8

# jcmd accepts the whole filename option as one argument, including paths with spaces.
$quotedFile = 'filename="' + $recording + '"'
$record = & $jcmdPath $JavaProcessId 'JFR.start' "name=avilixlogger_$Label" 'settings=profile' "duration=${Seconds}s" $quotedFile 'dumponexit=true' 2>&1
if ($LASTEXITCODE -ne 0) { throw "JFR.start failed: $record" }
$record | Out-Host
Write-Host "Recording started for $Seconds seconds: $recording"
Write-Host "After recording completes: java tools/acceptance/ProfileSummary.java `"$recording`""
