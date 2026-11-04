# 릴리스 방법

버전 번호는 **`version.json` 한 곳**에만 있습니다. Windows 앱(`pc/package.json`은 빌드 때 자동으로 맞춰짐)과
Android 앱(`versionName` = 버전, `versionCode` = 주×10000 + 부×100 + 수정, 예: 1.1.0 → 10100)이 모두 이 값을 씁니다.

## 새 버전 내기 (매번)

1. `version.json`의 `version`을 올립니다. 예: `1.1.0` → `1.1.1`
   (부 번호·수정 번호는 0~99, 한 번 낸 번호는 다시 쓰지 않습니다)
2. `release.json`의 `notes`에 바뀐 점을 한두 문장으로 적습니다.
3. 커밋해서 `main`에 push 합니다. 끝.

`Release` 워크플로가 알아서:
- 태그 `v<버전>`을 그 커밋에 붙이고 GitHub Release를 만들고,
- Windows 설치 프로그램(`MoaPlayer-Setup-<버전>.exe`, 자동 업데이트용 `latest.yml`, `.blockmap`)과
  Android `MoaPlayer-<버전>.apk`를 빌드해 첨부합니다. (보통 10~15분)

설치되어 있는 Windows 앱은 몇 시간 안에(또는 다음 실행 때) 새 버전을 받아 두었다가 종료할 때 설치하고,
Android 앱은 다음에 열 때 새 버전을 알려 줍니다.

- 버전을 올리지 않고 push 하면(문서 수정 등) 이미 완성된 릴리스는 다시 만들지 않습니다.
- 빌드가 실패했으면 고쳐서 다시 push 하세요. 빠진 플랫폼만 다시 빌드합니다.
- 같은 버전을 강제로 다시 빌드: Actions → **Release** → Run workflow → `force` 체크.
- 버전 끝에 `-beta.1`처럼 붙이면 "Pre-release"로 올라가며, 자동 업데이트 대상이 되지 않습니다.

## 처음 한 번만: Android 서명 키

Android는 **같은 키로 서명된 APK만** 기존 설치본 위에 업데이트로 설치됩니다. 키는 저장소에 넣지 않고
GitHub **Secrets**에 넣습니다. (Settings → Secrets and variables → Actions → **Secrets** 탭 → New repository secret)

| 이름 | 값 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 키스토어 파일(`release.jks`)을 base64로 바꾼 한 줄 |
| `ANDROID_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `ANDROID_KEY_ALIAS` | (선택) 키 별칭, 기본값 `moa` |
| `ANDROID_KEY_PASSWORD` | (선택) 키 비밀번호, 기본값 = 키스토어 비밀번호 |

base64 만들기:
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`
- macOS / Linux: `base64 -w0 release.jks` (macOS는 `base64 -i release.jks`)

Secrets가 없으면 **APK를 Release에 올리지 않습니다** (Windows 파일만 올라감). 디버그 키로 서명된 APK를 설치하면
나중에 정식 키 APK로 업데이트할 수 없기 때문입니다. Secrets를 넣은 뒤 Actions → **Release** → **Run workflow**를 누르거나
`main`에 아무 커밋이나 push 하면, 빠진 APK만 정식 키로 빌드해 올립니다.

> 키스토어 파일과 비밀번호는 꼭 따로 보관하세요. 잃어버리면 기존 사용자는 앱을 지웠다가 다시 설치해야 합니다.

로컬 빌드에서도 같은 키를 쓰려면 `android/keystore.properties`(커밋하지 않음)를 만듭니다:
```
storeFile=keystore/release.jks
storePassword=********
keyAlias=moa
keyPassword=********
```

## (선택) Windows 코드 서명

인증서가 있으면 Secrets `CSC_LINK`(pfx 파일의 base64 또는 URL), `CSC_KEY_PASSWORD`를 넣으세요.
설치 프로그램이 서명되어 "Windows의 PC 보호" 경고가 줄어듭니다. 없으면 서명 없이 빌드됩니다.

## 확인할 곳
- 진행 상황: 저장소 → **Actions** → Release
- 결과: 저장소 → **Releases** (`latest.yml`이 있어야 Windows 자동 업데이트가 동작)
- 업데이트 흐름 문제: Windows `%APPDATA%\MoaPlayer\logs\main.log`
