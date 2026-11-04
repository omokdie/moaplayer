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
| 8 | 기능 문제 없이 최적화, 용량 줄이기 | ✅ 1.3.0: FFmpeg를 직접 빌드(`pc/scripts/build-ffmpeg.sh`: 내장 디코더·디먹서 전부 + dav1d + x264, 필요한 인코더·먹서·필터만, 네트워크·장치 없음, 185MB→25MB), 언어 파일 ko·en-US만, `dxcompiler.dll` 제외, 최대 압축 → 설치 파일 179→115MB, 설치 후 545→312MB. (`nsis.differentialPackage: false`로 105MB까지 줄일 수 있지만 차분 업데이트가 없어져 되돌림. electron-builder가 7z 필터를 끄고 압축해서(-mf=off) 더 줄이기 어려움) 썸네일·자막 캐시 자동 정리(오래된 것·용량 초과분), probe 캐시 6000개 상한, 플레이어를 닫으면 자막 루프 정지. 모든 UI 테스트를 작은 FFmpeg로 통과 |
| 9 | 고화질 영상도 원본 화질 그대로 | ✅ 1.3.0: SW 변환에서 1080p 강제 축소 제거(설정으로 선택), CRF 20→17, 4K는 superfast, 고품질 sws_flags. HEVC Main10 지원 여부 따로 확인. Android: 스트림 해상도 제한(화면 크기) 해제. HW·SW 복사 모드는 원래 원본 그대로 |
| 10 | 휴대폰에서 누르면 즉시 재생, 나가면 즉시 멈춤 | ✅ 1.4.0 (Android): 누르는 순간 `PlayerEngine.prepareAhead()`가 파일을 열고(일시정지·화면 없이 디코딩 시작) 플레이어 화면은 그걸 이어받음(`takeAhead`). 라이브러리 화면에서 예비 ExoPlayer를 미리 만들고(`warmUp`, IdleHandler) 코덱 목록을 백그라운드로 채움, 로컬 파일 시작 버퍼 250ms, API 34+ `BUFFER_FLAG_DECODE_ONLY`, `FLAG_ACTIVITY_NO_ANIMATION`·닫기 애니메이션 없음·회전 JUMPCUT·아이콘 없는 검은 스플래시(values-v31). 이어보기는 트랙이 확인되면 최대 2초 앞 키프레임으로 맞춤(빠른 탐색 켜짐일 때, `maybeSnap`) — 열었다 바로 닫아도 원래 위치를 유지(`keepMs`). 종료: 뒤로·닫기·오류·끝 → `exitPlayer()`가 그 자리에서 일시정지+위치 저장+`stop()`(예전엔 onStop까지 약 1초 더 재생), 홈·최근 앱은 onPause에서 멈춤(PiP·멀티 윈도우·백그라운드 재생 제외, onStop이 안 오면 onResume에서 다시 재생). 새 화면이 넘겨받은 뒤 닫히던 이전 화면은 플레이어를 건드리지 않음(`superseded`), 저장은 지금 플레이어가 가진 파일일 때만(`isCurrentItem`) |
| 11 | 자동 업데이트 (휴대폰) | ✅ 1.4.0: `UpdateInstaller`가 APK를 백그라운드로 받아 크기·SHA-256(GitHub digest)·패키지·minSdk·versionCode(같은 값은 이름이 새로우면 허용: 베타→정식)·서명 인증서를 확인. 설치할 수 없는 버전(서명 다름·기기 미지원·번호 낮음·잘못된 파일)은 `update_blocked`로 기억해 다시 받지 않고 한 번만 설명. 무확인 설치 조건: Android 12+ · "이 출처 허용" · 새 APK의 targetSdk가 Android별 하한 이상(`targetAllowsSilent`: 12→29, 13→30, 14→31, 15→33, 16→34, 17→35, 그 이후는 대화상자) · 앱 화면이 하나도 안 보이고 플레이어가 아예 없음(홈 버튼·화면 끔으로 일시정지된 플레이어가 남아 있으면 그 영상이 닫히므로 설치하지 않음). 그러면 1분(화면 꺼짐이면 5초) 뒤 JobScheduler 작업(`UpdateJobService`, 백그라운드 프로세스가 얼어 있어도 실행됨)이 세션에 APK를 쓰고, 커밋 직전에 메인 스레드에서 다시 확인한 뒤 PackageInstaller(USER_ACTION_NOT_REQUIRED)로 설치(최대 2번, 실패·확인 필요 시 대화상자) → 다음 시작 때 "업데이트했습니다"(+알림). 그 밖에는 폴더 화면 대화상자 [지금 설치] → `ACTION_INSTALL_PACKAGE`(content:// `UpdateFileProvider`) → Android 설치 화면(처음엔 출처 허용 → 업데이트 → 열기). "나중에"는 하루 동안 다시 묻지 않음, 무확인 설치가 하루 넘게 안 되면 대화상자. 확인 주기 3시간(폴더 화면 onResume). Windows는 이미 자동(electron-updater) |

이름: 'MX Player' 이름·로고는 쓰지 않고 "모아 플레이어 / MoaPlayer"로 만들었습니다. 패키지 `com.moa.player`, Windows appId 동일.

## 2. 구조 요약

- `pc/` — Electron 앱. `main.js`(moa:// 프로토콜: 앱 파일·로컬 미디어 Range·`moa://stream` FFmpeg 변환 fMP4, IPC, 창, 작업 표시줄 버튼),
  `updater.js`(electron-updater, 설치본에서만), `renderer/app.js`(라이브러리·플레이어·제스처·단축키·업데이트 UI·Media Session),
  `lib/ffmpeg.js`(probe·썸네일·자막 추출·키프레임·스트림 인자). SW 모드에서 영상 복사 가능하면 `-c:v copy` + 키프레임부터 8~16배속 무음
  "따라잡기"로 정확한 위치 맞춤.
- `android/` — `PlayerEngine`(ExoPlayer 싱글턴, SW = 소프트웨어 코덱 우선 선택기), `PlaybackService`(포그라운드 서비스, 알림),
  `PlayerActivity`(UI·제스처·PiP), `UpdateChecker`(GitHub `releases/latest` 확인) + `UpdateInstaller`/`UpdateFileProvider`/`UpdateReceiver`/`UpdateJobService`
  (APK 내려받기·확인·설치, 위 11번). `App`이 보이는 화면 수(`App.visible`)를 세어 무확인 설치 시점을 정함.
- 배포: `.github/workflows/release.yml` → `build-windows.yml`(ubuntu `ffmpeg` 작업이 `pc/scripts/build-ffmpeg.sh`로 mingw-w64 교차 빌드,
  스크립트 해시로 캐시 → windows-latest 작업이 아티팩트를 `pc/ffmpeg`에 받아 설치 프로그램에 넣음) + `build-android.yml`(JDK 17, AGP 8.10.1, Gradle 8.14.3 wrapper).
  이 클라우드 세션에서는 태그 push가 막혀 있을 수 있어, 워크플로가 직접 태그를 만듭니다(버전 올려 main push).
  클라우드 세션은 GitHub Release를 직접 만들거나 지울 수 없고(403), Actions Secrets도 설정할 수 없습니다 — 모두 워크플로나 사용자가 합니다.
- Android 서명: Secrets(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, 별칭 기본 `moa`) — 2026-11-04 등록 완료, v1.2.0부터 정식 키 APK. 없으면 APK를 **릴리스에 올리지 않음**
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

1. Windows 실기 확인: 설치·제거, 파일 연결, 자동 업데이트(다음 버전을 낸 뒤 실제로 받아지는지), 작업 표시줄 버튼.
   클라우드에서는 Linux에서만 실행 테스트를 했습니다.
2. Android 실기 확인(에뮬레이터·실기기에서 돌려 본 적 없음 — 컴파일·API 레벨 검사·코드 리뷰까지만).
   1.4.0의 즉시 재생·즉시 종료, 그리고 자동 업데이트는 다음 버전(1.4.1 이상)을 낸 뒤 실제로 받아 설치되는지 확인 필요.
   Android 17(API 37)은 무확인 설치에 targetSdk 35 이상이 필요 → 지금(targetSdk 34)은 Android 17에서 대화상자 방식.
   targetSdk 35로 올리려면 Android 15의 edge-to-edge 강제(목록·설정 화면이 상태 표시줄 아래로 들어감)를 먼저 처리해야 함.
   이어보기 "매번 묻기"는 목록에서 열 때는 묻지 않고 바로 이어서 재생(즉시 재생 요구, 원래 그렇게 동작하던 것을 명시),
   플레이어 안에서 다음/이전 파일로 갈 때만 물음.
3. Windows 코드 서명 인증서 없음 → 처음 실행 시 SmartScreen 경고. (`CSC_LINK` Secret으로 서명 가능)
4. Android SW 디코더는 기기 코덱 사용 → AC3·DTS 음성은 기기에 따라 무음. (FFmpeg 확장 디코더를 넣으면 해결, APK 크기 증가)
5. Android 내장 자막에는 싱크 조절이 적용되지 않음.
6. GitHub Actions의 Node 20 계열 액션 경고가 나오면 새 주 버전으로 올리기.
