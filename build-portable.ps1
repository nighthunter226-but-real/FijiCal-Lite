param(
    [Parameter(Mandatory=$true)][string]$BasePortable,
    [Parameter(Mandatory=$true)][string]$JdkRoot
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$baseline = (Resolve-Path -LiteralPath $BasePortable).Path
$jdk = (Resolve-Path -LiteralPath $JdkRoot).Path
$buildRoot = Join-Path $projectRoot ('build\release-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
$portable = Join-Path $buildRoot 'FijiCal Lite'
foreach ($required in @('FijiCal Lite.exe','app\groovy-4.0.28.jar','app\groovy-json-4.0.28.jar','app\ij-1.54p.jar','runtime\bin\java.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $baseline $required))) { throw "Missing baseline component: $required" }
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null
Copy-Item -LiteralPath $baseline -Destination $portable -Recurse
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
$plugin = Join-Path $portable 'Export to FijiCal.lrplugin'
New-Item -ItemType Directory -Path $plugin -Force | Out-Null
Copy-Item -Path (Join-Path $projectRoot 'lightroom\Export to FijiCal.lrplugin\*') -Destination $plugin -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $portable 'README - Lightroom Bridge.md') -Force
$archive = Join-Path $buildRoot 'FijiCal Lite 0.9 - Mamanuca with Lightroom Bridge.zip'
Compress-Archive -LiteralPath $portable -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
Set-Content -LiteralPath ($archive + '.sha256') -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii
Write-Output $archive
Write-Output "SHA-256: $hash"
