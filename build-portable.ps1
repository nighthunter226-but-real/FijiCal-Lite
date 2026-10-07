# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
# Distributed without warranty; see LICENSE and COPYRIGHT.md.
param(
    [Parameter(Mandatory=$true)][string]$BasePortable,
    [Parameter(Mandatory=$true)][string]$JdkRoot,
    [string]$RuntimeSourceArchive
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RuntimeSourceArchive)) {
    throw 'Release blocked: provide -RuntimeSourceArchive with reviewed complete corresponding source for the Azul runtime and native launcher. See SOURCE_DISTRIBUTION.md; a JDK src.zip is insufficient.'
}
$runtimeSource = (Resolve-Path -LiteralPath $RuntimeSourceArchive).Path
if (!(Test-Path -LiteralPath $runtimeSource -PathType Leaf) -or
    [IO.Path]::GetFileName($runtimeSource) -eq 'src.zip' -or
    (Get-Item -LiteralPath $runtimeSource).Length -eq 0) {
    throw 'Provide a nonempty, reviewed complete runtime source archive, not JDK src.zip.'
}
$baseline = (Resolve-Path -LiteralPath $BasePortable).Path
$jdk = (Resolve-Path -LiteralPath $JdkRoot).Path
$buildRoot = Join-Path $projectRoot ('build\release-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
$portable = Join-Path $buildRoot 'FijiCal Lite'
foreach ($required in @('FijiCal Lite.exe','app\groovy-4.0.28.jar','app\groovy-json-4.0.28.jar','app\ij-1.54p.jar','runtime\bin\java.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $baseline $required))) { throw "Missing baseline component: $required" }
}
$runtimeIdentity = (& (Join-Path $baseline 'runtime\bin\java.exe') -XshowSettings:properties -version 2>&1 | Out-String)
if ($runtimeIdentity -notmatch 'Zulu21\.42\+19-CA' -or $runtimeIdentity -notmatch '21\.0\.7\+6-LTS') {
    throw 'Baseline runtime differs from the audited Azul build. Update the notices and review matching source before packaging.'
}
if (!(Test-Path -LiteralPath (Join-Path $baseline 'runtime\legal\java.base\LICENSE'))) {
    throw 'Baseline runtime licence files are missing.'
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null
Copy-Item -LiteralPath $baseline -Destination $portable -Recurse
$legacyScript = [IO.Path]::GetFullPath((Join-Path $portable 'app\FijiCal_Lite_Taveuni.groovy'))
if (!$legacyScript.StartsWith([IO.Path]::GetFullPath($buildRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Legacy script cleanup must stay inside the new build folder.'
}
if (Test-Path -LiteralPath $legacyScript) { Remove-Item -LiteralPath $legacyScript }
$classpath = Join-Path $portable 'app\*'
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $classes `
    (Join-Path $projectRoot 'launcher\src\org\fijical\lite\Main.java') `
    (Join-Path $projectRoot 'tests\org\fijical\lite\MainLaunchFilesTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed.' }
& (Join-Path $jdk 'bin\java.exe') -ea -cp "$classes;$classpath" org.fijical.lite.MainLaunchFilesTest
if ($LASTEXITCODE -ne 0) { throw 'Launcher tests failed.' }
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $portable 'app\FijiCalLauncher.jar') `
    --manifest (Join-Path $projectRoot 'launcher\launcher-manifest.mf') -C $classes org\fijical\lite\Main.class
if ($LASTEXITCODE -ne 0) { throw 'Launcher packaging failed.' }
Copy-Item -Path (Join-Path $projectRoot 'app\*') -Destination (Join-Path $portable 'app') -Force
& (Join-Path $jdk 'bin\java.exe') -cp $classpath groovy.ui.GroovyMain -e `
    "def file=new File(args[0]); new GroovyClassLoader(this.class.classLoader).parseClass(file.readLines('UTF-8').drop(1).join(System.lineSeparator()),file.name); println 'Mamanuca script compilation passed'" `
    (Join-Path $portable 'app\FijiCal_Lite_Mamanuca.groovy')
if ($LASTEXITCODE -ne 0) { throw 'Mamanuca script compilation failed.' }
$plugin = Join-Path $portable 'Export to FijiCal.lrplugin'
New-Item -ItemType Directory -Path $plugin -Force | Out-Null
Copy-Item -Path (Join-Path $projectRoot 'lightroom\Export to FijiCal.lrplugin\*') -Destination $plugin -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $portable 'README - Lightroom Bridge.md') -Force
# Preserve upstream notices, and expose Groovy's embedded notices as plain files.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$groovyNotices = Join-Path $portable 'licenses\groovy'
New-Item -ItemType Directory -Path $groovyNotices -Force | Out-Null
$groovyJar = [IO.Compression.ZipFile]::OpenRead((Join-Path $portable 'app\groovy-4.0.28.jar'))
try {
    foreach ($name in @('META-INF/LICENSE','META-INF/NOTICE','META-INF/licenses/antlr4-license.txt','META-INF/licenses/asm-license.txt')) {
        $entry = $groovyJar.GetEntry($name)
        if ($null -eq $entry) { throw "Missing Groovy notice: $name" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $groovyNotices ([IO.Path]::GetFileName($name))), $true)
    }
} finally { $groovyJar.Dispose() }
$sourceDirectory = Join-Path $portable 'source'
New-Item -ItemType Directory -Path $sourceDirectory -Force | Out-Null
foreach ($folder in @('app','launcher','lightroom','tests')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $folder) -Destination $sourceDirectory -Recurse
}
foreach ($document in @('LICENSE','COPYRIGHT.md','THIRD_PARTY_NOTICES.md','SOURCE_DISTRIBUTION.md','README.md','build-portable.ps1','AGENTS.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $document) -Destination $sourceDirectory -Force
}
foreach ($document in @('LICENSE','COPYRIGHT.md','THIRD_PARTY_NOTICES.md','SOURCE_DISTRIBUTION.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $document) -Destination $portable -Force
}
# This is a companion release asset: publish it alongside the portable ZIP.
$sourceAsset = Join-Path $buildRoot ('runtime-source-' + [IO.Path]::GetFileName($runtimeSource))
Copy-Item -LiteralPath $runtimeSource -Destination $sourceAsset
$archive = Join-Path $buildRoot 'FijiCal Lite 0.9 - Mamanuca with Lightroom Bridge.zip'
Compress-Archive -LiteralPath $portable -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
Set-Content -LiteralPath ($archive + '.sha256') -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii
Write-Output $archive
Write-Output "SHA-256: $hash"
Write-Output "Runtime source (publish alongside ZIP): $sourceAsset"
Write-Output ('Runtime source SHA-256: ' + (Get-FileHash -LiteralPath $sourceAsset -Algorithm SHA256).Hash)
