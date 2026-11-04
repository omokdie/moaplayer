# 모아 플레이어 — 작업 인계 문서 (Handoff)

> 갱신: 2026-11-04. 다음 세션은 이 문서 → `README.md` → `RELEASING.md` → `pc/README.md` · `android/README.md` 순서로 읽으면 됩니다.
> 원본: **GitHub `omokdie/moaplayer` (공개, main)**.

## 1. 사용자 요구사항 (반영 상태)

| # | 요구 | 상태 |
|---|---|---|
| 1 | MX Player와 똑같은 방식으로 쓰는 Android 앱 | ✅ Java + Media3 ExoPlayer 1.11.1. 폴더/목록, MX 제스처, HW/SW 디코더, SMI 등 자막, 백그라운드·PiP |
| 2 | 같은 방식의 PC 프로그램 | ✅ Electron 45 + FFmpeg. HW(Chromium)/SW(FFmpeg 변환 스트림) 디코더, 같은 제스처와 단축키 |
| 3 | Windows 버전을 "윈도우 앱 형태"로 | ✅ NSIS 설치 프로그램(시작 메뉴·바탕화면 바로가기, 앱 목록에서 제거, 설치 위치 선택), 동영상 파일 연결, 자동 업데이트, 어두운 제목 표시줄, 작업 표시줄 ⏮⏯⏭ 버튼, 미디어 키, 점프 목록 |
| 4 | JSE처럼 GitHub에 올리기 | ✅ 저장소 구성·Actions·Releases를 `omokdie/jse`와 같은 방식으로 (`version.json` 올려 main push → 자동 릴리스) |
| 5 | 영상 스킵할 때 딜레이 | ✅ 1.2.0 "빠른 탐색": 건너뛰기가 ±min(간격/2, 5초) 안의 키프레임으로 바로 이동. PC: `media:keyframes`(ffprobe 창 읽기, 캐시) + HW 긴 GOP 감지, SW 복사 모드는 따라잡기 없이 키프레임에서 시작, 연속 입력 합치기, fMP4 `-frag_duration`·x264 zerolatency. Android: `SeekParameters(tol, tol)` + 연속 탭 기준점 고정. Linux 측정: 긴 GOP HW 0.5–0.9→0.2초, SW 복사 0.5–2.5→0.3–0.5초 |
| 6 | 폴더 이름 변경·삭제 | ✅ PC: 폴더 오른쪽 클릭/⋮ (`lib:renameFolder`, `lib:deleteFolder` — 동영상+같은 이름 자막을 휴지통으로, 빈 폴더면 폴더도, Windows 기본 폴더 보호, 기록·라이브러리 경로 갱신). Android: 폴더 길게 누르기 (모든 파일 접근 권한 있으면 파일 API, 없으면 MediaStore 삭제 요청; 이름 변경은 권한 필요) |
| 7 | GitHub 작업은 사이트를 열어 직접 | ✅ 내장 브라우저로 진행. 단, 비밀번호·서명 키 값 입력은 Claude가 할 수 없어 사용자가 붙여 넣음 |

이름: 'MX Player' 이름·로고는 쓰지 않고 "모아 플레이어 / MoaPlayer"로 만들었습니다. 패키지 `com.moa.player`, Windows appId 동일.

## 2. 구조 요약

- `pc/` — Electron 앱. `main.js`(moa:// 프로토콜: 앱 파일·로컬 미디어 Range·`moa://stream` FFmpeg 변환 fMP4, IPC, 창, 작업 표시줄 버튼),
  `updater.js`(electron-updater, 설치본에서만), `renderer/app.js`(라이브러리·플레이어·제스처·단축키·업데이트 UI·Media Session),
  `lib/ffmpeg.js`(probe·썸네일·자막 추출·키프레임·스트림 인자). SW 모드에서 영상 복사 가능하면 `-c:v copy` + 키프레임부터 8~16배속 무음
  "따라잡기"로 정확한 위치 맞춤.
- `android/` — `PlayerEngine`(ExoPlayer 싱글턴, SW = 소프트웨어 코덱 우선 선택기), `PlaybackService`(포그라운드 서비스, 알림),
  `PlayerActivity`(UI·제스처·PiP), `UpdateChecker`(GitHub `releases/latest`의 APK 안내).
- 배포: `.github/workflows/release.yml` → `build-windows.yml`(windows-latest, FFmpeg는 `pc/scripts/fetch-ffmpeg.ps1`로 BtbN GPL shared
  빌드를 받아 `resources/ffmpeg`에 넣음) + `build-android.yml`(JDK 17, AGP 8.10.1, Gradle 8.14.3 wrapper).
  이 클라우드 세션에서는 태그 push가 막혀 있을 수 있어, 워크플로가 직접 태그를 만듭니다(버전 올려 main push).
  클라우드 세션은 GitHub Release를 직접 만들거나 지울 수 없고(403), Actions Secrets도 설정할 수 없습니다 — 모두 워크플로나 사용자가 합니다.
- Android 서명: Secrets(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, 별칭 기본 `moa`)가 없으면 APK를 **릴리스에 올리지 않음**
  (디버그 키 APK는 정식 키 APK로 업데이트가 안 되므로). v1.1.0 첫 빌드 때 올라간 디버그 키 APK는 release.yml의 정리 단계가 지웁니다.

## 3. 테스트 / 검증 방법

```bash
# PC UI 테스트 (Linux, Xvfb) — pc/README.md
bash pc/tests/make-testmedia.sh /tmp/moa-testmedia
MOA_TESTMEDIA=/tmp/moa-testmedia xvfb-run -a -s "-screen 0 1366x800x24" node pc/tests/smoke.js   # formats/gestures/screens/audiotracks
# Android: 클라우드 세션에서는 Gradle 불가 → android/build-local.sh (tools/cloud/README.md), 정식 빌드는 Actions
# APK 서명 확인
apksigner verify --print-certs MoaPlayer-x.y.z.apk     # 정식 키 SHA-256 042d45bb68b51232…19dd787c
```
릴리스 확인: `gh run list --repo omokdie/moaplayer`, `gh release view v<버전> --repo omokdie/moaplayer`.

## 4. 남은 일 / 알려진 제한

0. **사용자가 Android 서명 Secrets를 등록해야 APK가 Release에 올라갑니다** (RELEASING.md). 등록 뒤 Actions → Release → Run workflow.
1. Windows 실기 확인: 설치·제거, 파일 연결, 자동 업데이트(다음 버전을 낸 뒤 실제로 받아지는지), 작업 표시줄 버튼.
   클라우드에서는 Linux에서만 실행 테스트를 했습니다.
2. Android 실기 확인(에뮬레이터·실기기에서 돌려 본 적 없음 — 컴파일·API 레벨 검사·코드 리뷰까지만).
3. Windows 코드 서명 인증서 없음 → 처음 실행 시 SmartScreen 경고. (`CSC_LINK` Secret으로 서명 가능)
4. Android SW 디코더는 기기 코덱 사용 → AC3·DTS 음성은 기기에 따라 무음. (FFmpeg 확장 디코더를 넣으면 해결, APK 크기 증가)
5. Android 내장 자막에는 싱크 조절이 적용되지 않음.
6. GitHub Actions의 Node 20 계열 액션 경고가 나오면 새 주 버전으로 올리기.
