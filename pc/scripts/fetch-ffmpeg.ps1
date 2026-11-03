# Downloads the Windows FFmpeg build that ships inside the installer (SW decoder, thumbnails,
# embedded subtitles) into pc\ffmpeg\.  Used by the GitHub Actions build and for local builds:
#
#   cd pc
#   npm run fetch-ffmpeg
#
# Source: BtbN/FFmpeg-Builds (GPL, shared DLL build - smaller than the static one because
# ffmpeg.exe and ffprobe.exe share the same DLLs).  ffplay.exe is not needed and is skipped.
param(
  [string]$Url = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar

$pcDir = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $pcDir 'ffmpeg'
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('moa-ffmpeg-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
  $zip = Join-Path $tmp 'ffmpeg.zip'
  Write-Host "Downloading $Url"
  for ($i = 1; $i -le 3; $i++) {
    try { Invoke-WebRequest -Uri $Url -OutFile $zip -UseBasicParsing; break }
    catch { if ($i -eq 3) { throw }; Write-Host "retry $i ..."; Start-Sleep -Seconds (5 * $i) }
  }
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  $exe = Get-ChildItem -Path $tmp -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1
  if (-not $exe) { throw 'ffmpeg.exe not found in the archive' }
  $bin = $exe.DirectoryName
  if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  Get-ChildItem -Path $bin -File | Where-Object { $_.Name -ne 'ffplay.exe' } | Copy-Item -Destination $dest
  $license = Join-Path (Split-Path -Parent $bin) 'LICENSE.txt'
  if (Test-Path $license) { Copy-Item $license (Join-Path $dest 'LICENSE.txt') }
  & (Join-Path $dest 'ffmpeg.exe') -hide_banner -version | Select-Object -First 1
  if ($LASTEXITCODE -ne 0) { throw 'ffmpeg.exe does not run' }
  & (Join-Path $dest 'ffprobe.exe') -hide_banner -version | Select-Object -First 1
  if ($LASTEXITCODE -ne 0) { throw 'ffprobe.exe does not run' }
  $mb = [math]::Round(((Get-ChildItem $dest | Measure-Object -Property Length -Sum).Sum / 1MB), 1)
  Write-Host "FFmpeg ready in $dest ($mb MB)"
}
finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
