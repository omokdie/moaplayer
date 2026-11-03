# 클라우드 작업 환경용 빌드 도구

Claude 클라우드 세션처럼 **Maven/Google/npm 저장소에 접속할 수 없고 GitHub만 되는 환경**에서
Android APK와 Windows 배포본을 확인용으로 만들 때 쓴 도구입니다. 정식 배포 파일은 GitHub Actions가 만듭니다
(`.github/workflows/`). 인터넷이 정상인 PC에서는 이 폴더가 필요 없습니다.

| 파일 | 하는 일 |
|---|---|
| `build_media3.sh` | Media3 ExoPlayer(소스) + Guava를 javac로 컴파일하고 D8로 dex 변환 → `build-m3/lib-stripped.jar`, `build-m3/dex/classes.dex`. `../../android/build-local.sh`가 이 결과를 씁니다 |
| `StripParams.java` | ASM으로 클래스 파일의 MethodParameters 속성을 지움 (옛 D8이 깨지는 문제 회피) |
| `newapi_check.py` | 컴파일된 클래스에서 minSdk(24)보다 높은 API를 호출하는 곳을 `api-versions.xml`로 찾아 줌 |
| `pe_icon.py` | Windows exe의 아이콘 리소스를 바꿔 넣음 (electron-builder 없이 만든 portable 배포본용) |
| `stubs/` | 컴파일 전용 애노테이션/`sun.misc.Unsafe` 스텁 (AndroidX annotation, javax, kotlin 등) |

필요한 소스 (`/home/claude/tc` 아래에 git clone):

| 폴더 | 저장소 | 태그 |
|---|---|---|
| `media3` | https://github.com/androidx/media | `1.11.1` |
| `deps/guava` | https://github.com/google/guava | `v33.3.1` |
| `deps/errorprone` | https://github.com/google/error-prone | `v2.28.0` |
| `deps/checker` | https://github.com/typetools/checker-framework | `checker-framework-3.13.0` |
| `deps/j2objc` | https://github.com/google/j2objc | `2.8` |
| `Reginer_aosp-android-jar` | https://github.com/Reginer/aosp-android-jar | (android-36/android.jar) |

이 밖에 Linux용 Android build-tools 34.0.4(aapt2·d8·zipalign·apksigner)와 `platforms/android-33`(aapt2 링크용 프레임워크 리소스),
ASM(`/opt/gradle/lib/asm-*.jar`)이 필요합니다. Media3 패치 내용(BitmapUtil의 androidx ExifInterface → 프레임워크 ExifInterface,
HLS 광고 등 제외 파일)은 `build_media3.sh` 안에 있습니다.
