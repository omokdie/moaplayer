# 모아 플레이어 — Android 앱

MX Player와 같은 방식으로 쓰는 안드로이드 동영상 플레이어입니다.
재생 엔진은 Google의 **Media3 ExoPlayer 1.11.1**, UI는 안드로이드 기본 프레임워크(Java)로 만들었습니다.

## 설치 (APK)
1. [Releases](https://github.com/omokdie/moaplayer/releases/latest)에서 `MoaPlayer-x.y.z.apk`를 휴대폰으로 내려받습니다.
2. 파일을 눌러 설치합니다. "출처를 알 수 없는 앱" 경고가 나오면 해당 앱(브라우저/파일 관리자)에 설치 권한을 허용하세요.
3. 처음 실행하면 동영상 접근 권한을 허용합니다. Android 11 이상에서는 자막 파일(SMI·SRT)을 자동으로 불러오기 위해
   **모든 파일 접근** 권한을 묻습니다(설정에서 나중에 켜도 됩니다).
4. 새 버전이 나오면 앱을 열 때 알려 줍니다(설정 → 업데이트 → 새 버전 알림 / 지금 업데이트 확인).

- 지원: Android 7.0 (API 24) 이상

## 기능
| 구분 | 내용 |
|---|---|
| 라이브러리 | 폴더별 목록 → 동영상 목록(목록/격자), 썸네일·재생 시간·해상도, NEW 표시, 시청 진행 막대, 정렬(이름·날짜·크기·길이, 숫자 자연 정렬), 검색, 마지막 재생 이어보기 버튼, 동영상 길게 눌러 정보/공유/이름 변경/삭제, 폴더 길게 눌러 이름 변경/삭제(Android 11 이상은 "모든 파일 접근" 권한 필요) |
| 제스처 | 탭: 컨트롤 표시 · 좌/우 더블 탭: 10초 탐색(연속 탭 누적, 빠른 탐색: 가까운 키프레임으로 바로 이동) · 가운데 더블 탭: 재생/일시정지 · 좌우 스와이프: 탐색 · 왼쪽 상하: 밝기 · 오른쪽 상하: 볼륨(부스트 최대 +10단계) · 핀치: 확대/이동 · 길게 누르기: 2배속 |
| 컨트롤 | 잠금, 이전/재생/다음, 화면 비율(맞춤·늘이기·자르기·100%·16:9·4:3), 화면 회전, 재생 속도(0.25~4배), 남은 시간 표시 |
| 디코더 | **HW / SW 전환**(SW = 기기의 소프트웨어 코덱 우선). HW 디코딩 실패 시 SW로 자동 전환 |
| 자막 | 같은 이름의 SMI·SRT·ASS·SSA·VTT 자동 로드, 한글 EUC-KR(CP949)/UTF-8/UTF-16 자동 인식, SMI 다국어(한국어/영어) 선택, MKV·MP4 내장 자막(SRT·ASS·PGS·VobSub), 파일 선택기로 열기, 싱크 조절(파일별 저장), 크기·색·테두리·배경·위치 |
| 오디오 | 오디오 트랙 선택(언어·코덱·채널 표시), 지원 안 되는 코덱 안내 |
| 기타 | 이어보기(묻기/항상/안 함), 다음 파일 자동 재생, 반복(한 개/전체), A-B 구간 반복, 수면 타이머, 백그라운드 재생(알림 컨트롤), 팝업(PiP) 재생, 스크린샷(사진/MoaPlayer), 동영상 정보, 네트워크 스트림(HTTP·HLS·DASH·RTSP), 다른 앱의 "열기"로 재생, 새 버전 알림(GitHub Releases) |

## 소스에서 빌드
### Android Studio / Gradle
1. 이 `android/` 폴더를 Android Studio로 엽니다 (JDK 17, Android SDK 36).
2. Build ▸ Build APK(s), 또는 명령줄에서 `./gradlew assembleRelease`.
   - 버전은 저장소 맨 위의 `../version.json`에서 읽습니다.
   - 서명: 환경 변수 `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`(GitHub Actions) →
     `keystore.properties`(로컬, 커밋 금지) → 없으면 디버그 키. 자세한 내용은 [RELEASING.md](../RELEASING.md).

### Gradle 없이 (클라우드 작업 환경)
`build-local.sh`는 Android SDK 빌드 도구(aapt2·d8·apksigner)와 소스에서 컴파일한 Media3로 APK를 만드는
스크립트입니다. 인터넷이 막혀 Gradle을 쓸 수 없는 환경용이며, 도구 준비는 `../tools/cloud/`와 `../HANDOFF.md`를 보세요.

## 구조
```
app/src/main/java/com/moa/player/
  MainActivity          폴더 목록 (첫 화면), 권한 처리, 시작 시 업데이트 확인
  VideoListActivity     폴더 안 동영상 목록
  BaseLibraryActivity   정렬·검색·정보·공유·이름 변경·삭제 공통 기능
  PlayerActivity        플레이어 화면 (컨트롤, 제스처 처리, 자막/오디오/속도 등 메뉴, PiP)
  PlayerEngine          ExoPlayer와 재생 목록을 가진 싱글턴 (백그라운드 재생 유지, HW/SW 코덱 선택)
  PlaybackService       백그라운드 재생 포그라운드 서비스 + 미디어 알림
  GestureView           MX 방식 터치 제스처
  VideoFrameLayout      화면 비율·확대·이동 배치
  SubtitleParser/View   SMI·SRT·ASS·VTT 파서와 테두리 자막 렌더러
  UpdateChecker         GitHub Releases에서 새 APK 확인 → 내려받기 안내
  MediaRepo, ThumbLoader, HistoryDb, Prefs, SettingsActivity
```

## 알려진 제한
- SW 디코더는 기기에 들어 있는 소프트웨어 코덱을 씁니다. MX Player의 자체 FFmpeg 코덱과 달리, 기기가 지원하지 않는
  오디오(AC3·DTS 등)는 소리 없이 재생될 수 있습니다(재생 시 안내가 나옵니다).
- 내장 자막에는 싱크 조절이 적용되지 않고, 외부 자막에만 적용됩니다.
- 업데이트는 브라우저로 APK를 받아 직접 설치하는 방식입니다(Play 스토어 배포가 아니므로).
