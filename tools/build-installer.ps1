param([string]$Configuration = "Release")

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $repoRoot "pc\ZeroSynk\ZeroSynk.csproj"
$shellProject = Join-Path $repoRoot "pc\ZeroSynk.ShellExtension\ZeroSynk.ShellExtension.csproj"
$setupProject = Join-Path $repoRoot "tools\installer\ZeroSynk.Setup.csproj"
$portableOutput = Join-Path $repoRoot "publish-fd"
$shellOutput = Join-Path $repoRoot "pc\ZeroSynk.ShellExtension\bin\$Configuration\net8.0-windows"
$artifacts = Join-Path $repoRoot "release-assets"
$stage = Join-Path $env:TEMP ("zerosynk-package-" + [guid]::NewGuid().ToString("N"))
$payload = Join-Path $stage "payload"
$package = Join-Path $artifacts "ZeroSynk-Windows-x64.zip"
$setupOutput = Join-Path $artifacts "setup-output"
$setupExe = Join-Path $artifacts "ZeroSynk-Setup.exe"

try {
    New-Item -ItemType Directory -Force $payload, $artifacts | Out-Null
    $portableExe = Join-Path $portableOutput "ZeroSynk.exe"
    if (-not (Test-Path -LiteralPath $portableExe)) {
        throw "The latest ZeroSynk.exe was not found in publish-fd. Build it there first using the README command."
    }
    Copy-Item -LiteralPath $portableExe -Destination $payload -Force

    dotnet build $shellProject -c $Configuration
    if ($LASTEXITCODE -ne 0) { throw "Explorer shell extension build failed." }

    $shellFiles = @(
        "ZeroSynk.ShellExtension.comhost.dll",
        "ZeroSynk.ShellExtension.deps.json",
        "ZeroSynk.ShellExtension.runtimeconfig.json"
    )
    foreach ($name in $shellFiles) {
        $source = Join-Path $shellOutput $name
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing COM host file: $source" }
        Copy-Item -LiteralPath $source -Destination $payload -Force
    }
    if (-not (Test-Path (Join-Path $payload "ZeroSynk.exe"))) { throw "Published app executable is missing." }
    if (-not (Test-Path (Join-Path $payload "ZeroSynk.ShellExtension.dll"))) { throw "Published Explorer extension assembly is missing." }

    $projectXml = [xml](Get-Content -Raw $appProject)
    $version = $projectXml.Project.PropertyGroup.Version | Select-Object -First 1
    if (-not $version) { throw "Version was not found in the Windows project file." }
    Set-Content -LiteralPath (Join-Path $payload "version.txt") -Value $version -NoNewline

    if (Test-Path -LiteralPath $package) { Remove-Item -LiteralPath $package -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $payload, $package, [System.IO.Compression.CompressionLevel]::Optimal, $false)

    if (Test-Path -LiteralPath $setupOutput) { Remove-Item -LiteralPath $setupOutput -Recurse -Force }
    Write-Host "Publishing the self-contained latest-release installer..."
    dotnet restore $setupProject -r win-x64 --ignore-failed-sources
    if ($LASTEXITCODE -ne 0) { throw "Setup project restore failed." }
    dotnet publish $setupProject -c $Configuration -r win-x64 --self-contained true --no-restore `
        -p:PublishSingleFile=true -o $setupOutput
    if ($LASTEXITCODE -ne 0) { throw "Setup executable publish failed." }
    Copy-Item -LiteralPath (Join-Path $setupOutput "ZeroSynk-Setup.exe") -Destination $setupExe -Force

    Write-Host "Release assets ready:"
    Get-Item $setupExe, $package | Select-Object FullName, Length
    Write-Host "Upload both files to each GitHub Release. The setup downloads the package attached to the latest Release."
}
finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    if (Test-Path -LiteralPath $setupOutput) { Remove-Item -LiteralPath $setupOutput -Recurse -Force }
}
