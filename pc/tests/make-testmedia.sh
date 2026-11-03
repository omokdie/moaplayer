#!/bin/bash
# Builds the small test library used by the UI tests (needs ffmpeg with libx264, libx265, ac3, mp3).
#   bash tests/make-testmedia.sh [folder]        (default: $MOA_TESTMEDIA or /tmp/moa-testmedia)
set -euo pipefail
OUT="${1:-${MOA_TESTMEDIA:-/tmp/moa-testmedia}}"
mkdir -p "$OUT/드라마 폴더" "$OUT/Movies"
FF="ffmpeg -hide_banner -loglevel error -y"
src() { echo "testsrc2=size=$1:rate=$2:duration=$3"; }
tone() { echo "sine=frequency=$1:sample_rate=$2:duration=$3"; }

# H.264/AAC mp4 + SMI sidecar (Korean/English classes, CP949)
$FF -f lavfi -i "$(src 1280x720 30 120)" -f lavfi -i "$(tone 440 48000 120)" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -c:a aac -b:a 96k "$OUT/드라마 폴더/드라마 1화.mp4"
python3 - "$OUT/드라마 폴더/드라마 1화.smi" <<'EOF'
import sys
lines = ['<SAMI><HEAD><STYLE TYPE="text/css"><!--',
         '.KRCC { Name:한국어; lang:ko-KR; SAMIType:CC; }',
         '.ENCC { Name:English; lang:en-US; SAMIType:CC; }',
         '--></STYLE></HEAD><BODY>']
for i in range(1, 31):
    s = 500 + (i - 1) * 4000
    lines += [f'<SYNC Start={s}><P Class=KRCC>{i}번째 자막입니다.<br><font color="#ffff00">모아 플레이어</font> 테스트',
              f'<SYNC Start={s}><P Class=ENCC>Subtitle line {i}',
              f'<SYNC Start={s + 3000}><P Class=KRCC>&nbsp;',
              f'<SYNC Start={s + 3000}><P Class=ENCC>&nbsp;']
lines.append('</BODY></SAMI>')
open(sys.argv[1], 'w', encoding='cp949').write('\r\n'.join(lines) + '\r\n')
EOF

# MKV: H.264 1080p + AC3 (Korean, titled) + AAC (English) + embedded SRT
printf '1\n00:00:01,000 --> 00:00:04,000\n내장 자막 첫 줄\n\n2\n00:00:05,000 --> 00:00:08,000\n내장 자막 둘째 줄\n\n' > "$OUT/.embedded.srt"
$FF -f lavfi -i "$(src 1920x1080 25 60)" -f lavfi -i "$(tone 330 48000 60)" -f lavfi -i "$(tone 660 48000 60)" -i "$OUT/.embedded.srt" \
  -map 0:v -map 1:a -map 2:a -map 3:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 \
  -c:a:0 ac3 -b:a:0 192k -c:a:1 aac -b:a:1 128k -c:s srt \
  -metadata:s:a:0 language=kor -metadata:s:a:0 title="한국어 더빙" -metadata:s:a:1 language=eng -metadata:s:s:0 language=kor \
  "$OUT/드라마 폴더/드라마 2화 (AC3 멀티오디오).mkv"
rm -f "$OUT/.embedded.srt"

# HEVC mkv, MPEG-4/MP3 avi, portrait mp4
$FF -f lavfi -i "$(src 1280x720 30 30)" -f lavfi -i "$(tone 550 44100 30)" -c:v libx265 -preset ultrafast -x265-params log-level=error \
  -pix_fmt yuv420p -c:a aac "$OUT/Movies/HEVC 샘플.mkv"
$FF -f lavfi -i "$(src 720x480 30 45)" -f lavfi -i "$(tone 500 44100 45)" -c:v mpeg4 -q:v 5 -c:a libmp3lame -b:a 128k "$OUT/Movies/옛날 영화.avi"
$FF -f lavfi -i "$(src 720x1280 30 20)" -f lavfi -i "$(tone 600 44100 20)" -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac "$OUT/Movies/세로 영상.mp4"

# two AAC tracks with different tones (audiotracks.js checks the switch by frequency); kept outside the library folders
$FF -f lavfi -i "$(src 640x360 30 20)" -f lavfi -i "$(tone 440 44100 20)" -f lavfi -i "$(tone 1000 44100 20)" \
  -map 0:v -map 1:a -map 2:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac \
  -metadata:s:a:0 language=kor -metadata:s:a:1 language=eng "$OUT/dual-audio.mp4"
echo "test media ready in $OUT"
