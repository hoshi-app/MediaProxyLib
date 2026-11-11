# Сборка и прогон ядра MediaProxy без Gradle и сети: кэшированный Kotlin-компилятор + JDK.
# Тесты — assert-раннер tv.mediaproxy.core.SelfTest (exit code 1 при провале).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$groupPath) {
    $item = Get-ChildItem -Path (Join-Path $cache $groupPath) -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $item) { throw "не найден jar: $groupPath" }
    return $item.FullName
}

$compiler = Find-Jar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$coroutines = Find-Jar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2'
$trove = Find-Jar 'org.jetbrains.intellij.deps\trove4j\1.0.20200330'
$annotations = Find-Jar 'org.jetbrains\annotations\23.0.0'
$stdlib = Find-Jar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$okhttp = Find-Jar 'com.squareup.okhttp3\okhttp\4.12.0'
$okio = Find-Jar 'com.squareup.okio\okio-jvm\3.6.0'

$out = Join-Path $root 'build-core'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
$mainOut = Join-Path $out 'main'
$testOut = Join-Path $out 'test'
New-Item -ItemType Directory -Force -Path $mainOut, $testOut | Out-Null

$mainSrc = Join-Path $root 'android\src\main\java\tv\mediaproxy\core'
$testSrc = Join-Path $root 'android\src\test\java\tv\mediaproxy'
$mainFiles = @(Get-ChildItem $mainSrc -Recurse -Filter *.kt | ForEach-Object { $_.FullName })
$testFiles = if (Test-Path $testSrc) {
    @(Get-ChildItem $testSrc -Recurse -Filter *.kt | ForEach-Object { $_.FullName })
} else {
    @()
}
if ($mainFiles.Count -eq 0) { throw "нет исходников в $mainSrc" }

$runtime = "$stdlib;$okhttp;$okio"
$compilerCp = "$compiler;$stdlib;$coroutines;$trove;$annotations"

Write-Host "[build] компилирую ядро ($($mainFiles.Count) файлов)"
$mainArgs = @('-cp', $compilerCp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-nowarn', '-jvm-target', '17', '-classpath', $runtime, '-d', $mainOut) + $mainFiles
& java @mainArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($testFiles.Count -gt 0) {
    Write-Host "[build] компилирую тесты ($($testFiles.Count) файлов)"
    $testArgs = @('-cp', $compilerCp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-nowarn', '-jvm-target', '17', '-classpath', "$runtime;$mainOut", '-d', $testOut) + $testFiles
    & java @testArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host "[test] запускаю SelfTest"
    & java -cp "$runtime;$mainOut;$testOut" tv.mediaproxy.core.SelfTestKt
    exit $LASTEXITCODE
}

Write-Host "[build] тестов нет — только компиляция"
exit 0
