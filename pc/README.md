# 모아 플레이어 — Windows 앱 (Electron + FFmpeg)

MX Player와 같은 방식으로 쓰는 PC용 동영상 플레이어입니다. 설치 프로그램(`MoaPlayer-Setup-x.y.z.exe`),
동영상 파일 연결, GitHub Releases 자동 업데이트를 제공합니다.

- **HW 디코더**: Chromium 내장 디코더(하드웨어 가속) — MP4/MKV/WebM 안의 H.264·H.265·VP9·AV1 + AAC·MP3·Opus·FLAC
- **SW 디코더**: 함께 설치되는 FFmpeg로 실시간 변환 — AVI·WMV·FLV·TS·RMVB, AC3·DTS 음성, PGS 이미지 자막 등.
  HW로 안 되는 파일은 자동 전환됩니다. 영상은 그대로 두고 음성만 바꾸면 되는 파일은 영상 스트림을 복사해
  CPU를 거의 쓰지 않습니다(탐색 위치는 키프레임 뒤 정확한 지점까지 맞춤).

## 빌드 (Windows)

1. [Node.js 22 LTS](https://nodejs.org/) 설치
2. FFmpeg를 `pc\ffmpeg\`에 넣습니다. 배포판은 **`scripts/build-ffmpeg.sh`로 만든 작은 빌드**(약 30MB: 내장 디코더·디먹서 전부 +
   dav1d(AV1) + x264, 앱이 쓰는 인코더·필터만)를 GitHub Actions가 Ubuntu에서 mingw-w64로 빌드해 넣습니다
   (Actions → Build Windows installer의 `ffmpeg-win64` 아티팩트). 직접 빌드하기 번거로우면 `npm run fetch-ffmpeg`로
   BtbN 전체 빌드(약 185MB)를 받아도 똑같이 동작합니다.
3. 명령 프롬프트 / PowerShell에서
   ```bat
   cd pc
   npm install
   npm run dist:win
   ```
4. 결과: `pc\dist\MoaPlayer-Setup-<버전>.exe` (같은 폴더의 `latest.yml`, `*.blockmap`은 자동 업데이트용)

용량을 줄이는 설정(`package.json` → `build`): 언어 파일은 한국어·영어만(`electronLanguages`), WebGPU 셰이더 컴파일러
`dxcompiler.dll` 제외(`scripts/after-pack.cjs`), 최대 압축(`compression: maximum`). 결과: 설치 파일 약 115MB, 설치 후 약 310MB
(1.2.0까지는 180MB / 545MB). 자동 업데이트는 `.blockmap`으로 바뀐 부분만 내려받습니다.

개발 중 바로 실행: `npm start` (`pc\ffmpeg` 또는 PATH의 FFmpeg 사용).
실행 옵션: `--moa-library=<폴더>`(라이브러리 폴더 지정), `--moa-userdata=<폴더>`(설정 저장 위치).

## 설치되면

| 항목 | 위치 / 동작 |
|---|---|
| 프로그램 | `%LOCALAPPDATA%\Programs\MoaPlayer\MoaPlayer.exe` (사용자별 설치, 관리자 권한 불필요, 설치 위치 변경 가능) |
| FFmpeg | `<설치 폴더>\resources\ffmpeg\` (`ffmpeg.exe`, `ffprobe.exe`, DLL, `LICENSE.txt`) — `scripts/build-ffmpeg.sh`의 작은 빌드 |
| 화질 | HW: 원본 그대로. SW 복사(영상은 재생 가능, 음성만 변환): 원본 그대로. SW 변환(10비트 H.264, DivX·WMV 등): 원본 해상도, x264 CRF 17(눈으로 구분 어려운 화질), 4K는 빠른 프리셋. 설정 → SW 디코더 화질에서 1080p로 낮출 수 있음 |
| 바로가기 | 시작 메뉴·바탕화면 '모아 플레이어' |
| 파일 연결 | mp4 mkv avi mov wmv flv webm m2ts mts mpg mpeg vob 3gp ogv rm rmvb asf divx f4v tp trp → ProgID `MoaPlayer.Video`. 탐색기 "연결 프로그램"에 표시되고, 기본 앱이 정해져 있지 않은 형식은 더블클릭하면 바로 재생됩니다 |
| 설정·기록·썸네일 | `%APPDATA%\MoaPlayer\` (제거해도 남음) |
| 로그 | `%APPDATA%\MoaPlayer\logs\main.log` (자동 업데이트) |
| 자동 업데이트 | 시작 10초 후, 이후 4시간마다 GitHub Releases의 `latest.yml` 확인 → 새 버전을 받아 두었다가 종료할 때 설치. 설정 → 업데이트에서 끄기·바로 설치 |
| Windows 연동 | 어두운 제목 표시줄, 작업 표시줄 미리보기의 이전/재생/다음 버튼, 키보드 미디어 키·볼륨 팝업의 제목 표시(Media Session), 점프 목록의 최근 파일 |

이미 실행 중일 때 파일을 더블클릭하면 새 창 대신 기존 창에서 재생합니다(단일 인스턴스).

## 구조
```
main.js          메인 프로세스: moa:// 프로토콜(앱 파일, 로컬 미디어 Range 응답, SW 변환 스트림), IPC, 창,
                 작업 표시줄 버튼, 점프 목록
updater.js       자동 업데이트 (electron-updater, GitHub Releases) — 설치본에서만 동작
preload.js       렌더러에 허용된 IPC 채널만 노출
lib/ffmpeg.js    ffprobe 정보, 썸네일, 내장 자막 추출, 키프레임 조회, SW 변환 스트림 인자
lib/library.js   폴더 검색(폴더별 묶음), 같은 이름 자막 찾기
lib/store.js     설정·재생 기록 JSON 저장
renderer/        화면 (app.js: 라이브러리·플레이어·제스처·단축키·업데이트 표시, subtitles.js: SMI/SRT/ASS/VTT 파서)
assets/          창 아이콘, 작업 표시줄 버튼 아이콘(thumbar/ 어두운 작업 표시줄용, thumbar/light/ 밝은 작업 표시줄용
                 — tools/make-thumbar-icons.py)
build/           설치 프로그램 아이콘 (electron-builder buildResources)
scripts/         sync-version.mjs (../version.json → package.json), build-ffmpeg.sh (배포용 작은 FFmpeg),
                 after-pack.cjs (설치본에서 dxcompiler.dll 제외), fetch-ffmpeg.ps1 (개발용 전체 FFmpeg)
tests/           Playwright UI 테스트 (Linux + Xvfb) — 아래 참고
```

## UI 테스트 (Linux)
```bash
bash tests/make-testmedia.sh /tmp/moa-testmedia        # ffmpeg로 테스트 동영상 생성
npm i --no-save playwright-core electron
TARGET=linux bash scripts/build-ffmpeg.sh /tmp/ffmin                # 배포판과 같은 구성의 FFmpeg (Linux용)
export MOA_FFMPEG_DIR=/tmp/ffmin MOA_TESTMEDIA=/tmp/moa-testmedia MOA_SHOTS=/tmp/moa-shots
xvfb-run -a -s "-screen 0 1366x800x24" node tests/smoke.js      # formats, gestures, screens, audiotracks 도 같은 방식
```
