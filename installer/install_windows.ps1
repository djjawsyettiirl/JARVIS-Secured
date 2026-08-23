# JARVIS Windows bootstrap installer.
# Run from an elevated PowerShell window. This does not disable Windows security controls.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$HostDir = Join-Path $Root 'host'
$DataDir = Join-Path $env:ProgramData 'JARVIS'
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

Write-Host 'Installing JARVIS host dependencies...'
py -m pip install -r (Join-Path $HostDir 'requirements.txt')

# Keep the development gateway reachable from the private LAN only.
# Internet exposure should be done through a TLS tunnel/reverse proxy.
$ruleName = 'JARVIS Host - Private LAN 8765'
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private | Out-Null

Write-Host ''
Write-Host 'JARVIS host installed.'
Write-Host 'Local control panel: http://127.0.0.1:8766'
Write-Host 'LAN gateway:        http://<PC-IP>:8765'
Write-Host 'For internet access use a TLS tunnel/reverse proxy; do not port-forward 8765 directly.'
