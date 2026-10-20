# PowerShell equivalent of run_experiments.sh
#   .\run_experiments.ps1 --scenario mixed --repeats 5
#   .\run_experiments.ps1 --scenario mixed --quick
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$needsJars = $true
foreach ($a in $args) { if ($a -in @("docker", "attach", "--dry-run", "-h", "--help")) { $needsJars = $false } }
if ($needsJars -and -not (Test-Path "order-service\target\order-service.jar")) {
    Write-Host "building services (mvn clean install -DskipTests) ..."
    mvn -q -B clean install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
python -m harness @args
exit $LASTEXITCODE
