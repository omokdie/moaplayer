# 모아 플레이어 (Moa Player)

MX Player와 같은 방식으로 쓰는 **Android · Windows 동영상 플레이어**입니다.
화면 왼쪽을 쓸어 올리면 밝기, 오른쪽은 볼륨, 좌우로 밀면 탐색, 두 번 두드리면 10초 이동 —
휴대폰과 PC에서 똑같이 쓸 수 있습니다.

## 내려받기

**[최신 버전 내려받기 (Releases)](https://github.com/omokdie/moaplayer/releases/latest)**

| 기기 | 파일 | 설치 |
|---|---|---|
| Windows 10 / 11 (64비트) | `MoaPlayer-Setup-x.y.z.exe` | 내려받아 실행 → 설치 위치 확인 → **설치**. 시작 메뉴와 바탕화면에 '모아 플레이어'가 생깁니다. |
| Android 7.0 이상 | `MoaPlayer-x.y.z.apk` | 휴대폰에서 내려받아 파일을 눌러 설치. "출처를 알 수 없는 앱" 경고가 나오면 브라우저(또는 파일 관리자)에 설치를 허용하세요. |

- Windows에서 처음 실행할 때 **"Windows의 PC 보호"** 창이 뜨면 **추가 정보 → 실행**을 누르세요.
  코드 서명 인증서가 없는 개인용 프로그램이라 표시되는 경고입니다.
- 설치 후에는 **자동으로 업데이트**됩니다.
  - Windows: 인터넷에 연결되어 있으면 새 버전을 백그라운드에서 받아 두었다가, 프로그램을 닫을 때 설치합니다
    (설정 → 업데이트 → "다시 시작해서 새 버전 설치"로 바로 설치할 수도 있습니다).
  - Android: 새 버전이 나오면 앱을 열 때 알려 주고, [다운로드]를 누르면 새 APK를 받습니다.
    설정과 재생 기록은 그대로 남습니다.
- 제거: Windows 설정 → 앱 → 설치된 앱 → '모아 플레이어' → 제거. (설정·재생 기록은 `%APPDATA%\MoaPlayer`에 남습니다.)

## 기능

| 구분 | Android | Windows |
|---|---|---|
| 라이브러리 | 폴더별 목록 → 동영상 목록(목록/격자), 썸네일·재생 시간·해상도, NEW 표시, 시청 진행 막대, 정렬, 검색, 마지막 영상 이어보기, 폴더 길게 눌러 이름 변경·삭제 | 같음 + 폴더 추가(끌어다 놓기), 오른쪽 클릭·⋮ 메뉴(정보·탐색기에서 열기·이름 변경·삭제, 폴더 이름 변경·삭제는 휴지통으로) |
| 제스처 | 탭: 컨트롤 · 좌/우 더블 탭: 10초 탐색(누적) · 가운데 더블 탭: 재생/일시정지 · 좌우 스와이프: 탐색 · 왼쪽 상하: 밝기 · 오른쪽 상하: 볼륨(부스트) · 핀치: 확대 · 길게 누르기: 2배속 | 같은 제스처를 마우스 드래그·터치스크린으로, 휠: 볼륨, Ctrl+휠: 확대 |
| 디코더 | **HW / SW 전환** (HW 실패 시 SW로 자동 전환) | **HW**(Chromium 하드웨어 가속) / **SW**(내장 FFmpeg: AVI·WMV·FLV·TS·RMVB, AC3·DTS 음성 등) |
| 자막 | 같은 이름의 SMI·SRT·ASS·SSA·VTT 자동, 한글 CP949/UTF-8/UTF-16 자동 인식, SMI 다국어, 내장 자막, 싱크·크기·색·위치 | 같음 + PGS 이미지 자막(SW), 자막 파일 끌어다 놓기 |
| 재생 | 이어보기, 다음 파일 자동 재생, 반복, A-B 반복, 속도 0.25~4배, 화면 비율, 수면 타이머, 스크린샷, 동영상 정보, 네트워크 스트림(HTTP·HLS·DASH·RTSP), 빠른 탐색(건너뛸 때 가까운 키프레임으로 바로 이동) | 같음 + 항상 위, 전체 화면, PiP |
| 백그라운드 | 백그라운드 재생(알림 컨트롤), 팝업(PiP) | 작업 표시줄 미리보기의 ⏮ ⏯ ⏭ 버튼, 키보드 미디어 키, 점프 목록의 최근 파일 |
| 설치·업데이트 | APK, 새 버전 알림 | 설치 프로그램, 동영상 파일 연결(더블클릭으로 재생, "연결 프로그램"), 자동 업데이트 |

### Windows 단축키
`Space` 재생/일시정지 · `←/→` 10초 이동(`Shift` 60초) · `↑/↓` 볼륨 · `F`/`Enter` 전체 화면 · `Esc` 뒤로 ·
`N`/`P` 다음/이전 파일 · `[` `]` 속도 · `\` 원래 속도 · `A` 오디오 트랙 · `S` 자막 · `Z`/`X` 자막 싱크 -/+0.1초 ·
`H` HW/SW 디코더 · `R` 화면 비율 · `L` 잠금 · `B` A-B 반복 · `C` 스크린샷 · `I` 정보 · `T` 항상 위 · `1`~`9` 위치 이동 · `Ctrl+O` 파일 열기

## 폴더 구성

```
version.json            앱 버전 (Windows·Android 공통) — 올려서 main 에 push 하면 자동 릴리스
release.json            릴리스 노트 (GitHub Release 본문)
pc/                     Windows 앱 (Electron + FFmpeg) — pc/README.md
android/                Android 앱 (Java, Media3 ExoPlayer) — android/README.md
.github/workflows/      release.yml(릴리스 총괄) · build-windows.yml(설치 프로그램) · build-android.yml(APK)
tools/                  아이콘 생성, 클라우드 작업 환경용 빌드 도구 (HANDOFF.md 참고)
```

## 직접 빌드하기

| 대상 | 방법 | 결과 |
|---|---|---|
| Windows 설치 프로그램 | Windows + Node.js 22: `cd pc` → `npm install` → `npm run fetch-ffmpeg` → `npm run dist:win` | `pc/dist/MoaPlayer-Setup-<버전>.exe` |
| Windows 개발 실행 | `cd pc` → `npm install` → `npm start` (FFmpeg는 `pc/ffmpeg/` 또는 PATH) | — |
| Android APK | Android Studio로 `android/` 열기 → Build ▸ Build APK(s), 또는 `cd android && ./gradlew assembleRelease` (JDK 17) | `android/app/build/outputs/apk/release/` |

릴리스(버전 올리기, 서명 키 설정)는 [RELEASING.md](RELEASING.md), 다음 작업자를 위한 인계 문서는 [HANDOFF.md](HANDOFF.md)를 보세요.

## 사용한 구성 요소와 라이선스
- [Electron](https://www.electronjs.org/) (MIT), [electron-builder / electron-updater](https://www.electron.build/) (MIT)
- [FFmpeg](https://ffmpeg.org/) (GPL) — Windows 설치본에 [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds)의 GPL 빌드가
  `resources\ffmpeg`로 들어갑니다. 라이선스 전문은 같은 폴더의 `LICENSE.txt`, 소스는 ffmpeg.org와 BtbN 저장소에서 받을 수 있습니다.
- [AndroidX Media3 ExoPlayer](https://github.com/androidx/media) (Apache 2.0)
- 'MX Player'는 해당 권리자의 상표이며, 이 프로젝트는 MX Player와 관계가 없습니다. 조작 방식만 참고했습니다.
