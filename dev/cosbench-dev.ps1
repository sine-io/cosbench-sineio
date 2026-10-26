[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart')]
    [string]$Action = 'start',

    [ValidateSet('controller', 'driver', 'all')]
    [string]$Service = 'all',

    [switch]$NoBuild,

    [switch]$ResetRuntime
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime = Join-Path $root 'out\cosbench-dev'
$pidDirectory = Join-Path $runtime 'pids'

function Get-Java8 {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $candidates += @(
        'C:\Program Files\Java\jdk-1.8\bin\java.exe',
        'C:\Program Files\Java\jdk1.8.0_261\bin\java.exe',
        'C:\Program Files\Java\jdk1.8.0_202\bin\java.exe'
    )

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path $candidate) {
            $versionOutput = (& cmd.exe /d /c "`"$candidate`" -version 2>&1" | Out-String)
            if ($versionOutput -match 'version "1\.8\.') {
                return $candidate
            }
        }
    }

    throw 'JDK 8 is required. Set JAVA_HOME to a JDK 8 installation.'
}

function Get-Maven {
    if ($env:MAVEN_HOME) {
        $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $command = Get-Command mvn -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw 'Maven is required. Install Maven 3.9.x or set MAVEN_HOME.'
}

function Get-PidFile([string]$name) {
    return Join-Path $pidDirectory "$name.pid"
}

function Stop-ServiceProcess([string]$name) {
    $pidFile = Get-PidFile $name
    if (!(Test-Path $pidFile)) {
        return
    }

    $processId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue

    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $processId -Force
        $process.WaitForExit()
    }
}

function Stop-SelectedServices {
    if ($Service -in @('controller', 'all')) {
        Stop-ServiceProcess 'controller'
    }
    if ($Service -in @('driver', 'all')) {
        Stop-ServiceProcess 'driver'
    }
}

function Invoke-Build {
    $java = Get-Java8
    $maven = Get-Maven
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent

    Write-Host "Building OSGi bundles with $java ..."
    & $maven -B -ntp -f (Join-Path $root 'pom.xml') package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
}

function Sync-Runtime {
    if ($ResetRuntime -and (Test-Path $runtime)) {
        Remove-Item -LiteralPath $runtime -Recurse -Force
    }

    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    New-Item -ItemType Directory -Path $pidDirectory -Force | Out-Null

    foreach ($directory in @('main', 'osgi')) {
        $source = Join-Path $root "dist\$directory"
        $destination = Join-Path $runtime $directory
        if (!(Test-Path $source)) {
            throw "Missing runtime directory: $source"
        }
        Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
    }

    $releaseConf = Join-Path $root 'release\conf'
    Copy-Item -LiteralPath $releaseConf -Destination (Join-Path $runtime 'conf') -Recurse -Force
    New-Item -ItemType Directory -Path (Join-Path $runtime 'log') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $runtime 'archive') -Force | Out-Null

    $pluginDirectory = Join-Path $runtime 'osgi\plugins'
    $bundles = Get-ChildItem -Path (Join-Path $root 'dev') -Recurse -File -Filter 'cosbench-*.jar' |
        Where-Object { $_.FullName -match '\\target\\' }
    if ($bundles.Count -ne 30) {
        throw "Expected 30 built COSBench bundles, found $($bundles.Count). Run the build first."
    }
    foreach ($bundle in $bundles) {
        Copy-Item -LiteralPath $bundle.FullName -Destination $pluginDirectory -Force
    }
}

function Wait-ForPort([int]$port, [int]$processId) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $listening = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $processId }
        if ($listening) {
            return
        }

        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (!$process) {
            throw "COSBench process exited while waiting for port $port. Check $runtime\log."
        }
        Start-Sleep -Seconds 1
    }

    throw "COSBench did not open port $port within 60 seconds. Check $runtime\log."
}

function Start-ServiceProcess([string]$name, [string]$tomcatConfig, [string]$configuration, [int]$consolePort, [int]$webPort) {
    $java = Get-Java8
    $stdout = Join-Path $runtime "log\$name.stdout.log"
    $stderr = Join-Path $runtime "log\$name.stderr.log"
    $arguments = @(
        "-Dcosbench.tomcat.config=$tomcatConfig",
        '-server',
        '-cp',
        'main/*',
        'org.eclipse.equinox.launcher.Main',
        '-configuration',
        $configuration,
        '-console',
        "$consolePort"
    )

    $process = Start-Process -FilePath $java -WorkingDirectory $runtime -ArgumentList $arguments `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    Set-Content -LiteralPath (Get-PidFile $name) -Value $process.Id -NoNewline
    Wait-ForPort $webPort $process.Id
    Write-Host "$name is running at http://127.0.0.1:$webPort/$name/index.html"
}

if ($Action -in @('stop', 'restart')) {
    Stop-SelectedServices
    if ($Action -eq 'stop') {
        Write-Host 'COSBench development services stopped.'
        exit 0
    }
}

if (!$NoBuild) {
    Invoke-Build
}

Sync-Runtime

if ($Service -in @('driver', 'all')) {
    Start-ServiceProcess 'driver' 'conf/driver-tomcat-server.xml' 'conf/.driver' 18089 18088
}
if ($Service -in @('controller', 'all')) {
    Start-ServiceProcess 'controller' 'conf/controller-tomcat-server.xml' 'conf/.controller' 19089 19088
}

Write-Host "Runtime directory: $runtime"
Write-Host 'Use -Action stop to stop the selected service(s).'
