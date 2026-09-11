# Android Also Could Fold

[English](README.md) | **한국어**

**One UI는 그대로. 접고 펼치는 순간만 더 부드럽게.**

Galaxy Z Fold의 접힘·펼침 움직임에 맞춰 실제 화면에 시스템 블러를 적용하는
실험적인 Android 앱입니다. Shizuku의 ADB 권한으로 compositor를 제어하며,
화면 캡처나 런처 교체 없이 홈 화면, 앱, 잠금 화면 위에서 동작합니다.

## 동작

- **커버 펼침:** 왼쪽은 약하게, 오른쪽으로 갈수록 강한 블러.
- **내부 화면:** 왼쪽 절반 안에서 바깥쪽은 강하게, 중앙 힌지 쪽은 약하게.
- 각도와 움직임 신호에 따른 강도 변화. 앱에서 전체 강도 **50–150%** 조절.
- 마지막 움직임 감지 후 **1.5초 정지 → 420ms 동안 자연스럽게 해제**.
- 센서로 확인되는 역방향 움직임에서도 부드럽게 해제.
- 화면이 꺼져 있거나 AOD 상태이면 효과를 표시하지 않음.
- 앱을 닫아도 유지되는 Shizuku daemon, 실행 설정 저장, 재연결 복구, 알림의 중지 버튼.

## 지원 범위

현재 실제 검증 기기는 **Galaxy Z Fold7 SM-F966N / Android 16**입니다.
다른 기기에서는 실행을 차단합니다. Samsung의 비공개 SurfaceControl API에
의존하므로 One UI 업데이트 후 호환성 확인이 필요합니다.

공개 힌지 센서는 이 기기에서 주로 **0 / 90 / 180도**만 전달합니다.
내부 닫힘의 조기 감지는 짧은 흔들림을 줄이기 위해 움직임 신호가 한 번 더
이어지는지 확인합니다. 커버 펼침보다 약 0.5초 늦을 수 있으며, 실제 각도 변화가
보고되면 추가 대기 없이 시작합니다.

초기 움직임은 `dumpsys sensorservice`의 vendor 이벤트 시각으로 추정하며,
숨겨진 연속 각도 값을 읽는 방식은 아닙니다. 아주 느린 움직임이나 작은 역회전은
구분하지 못할 수 있습니다. 켜진 화면에서 4Hz 진단 폴링을 사용하며 장시간 배터리
영향은 아직 측정하지 않았습니다.

## 앱 설치 없이 먼저 체험하기

**Fold Transition APK와 Shizuku를 설치하지 않고도 같은 블러 효과를 시험할 수 있습니다.**
PC에서 ADB로 임시 엔진을 실행하는 방식이며, 기본 **10분 후 자동 종료**됩니다.
루팅이나 Wi-Fi 페어링은 필요하지 않습니다.

PC에 Python 3, JDK 17 이상, Android SDK Platform 36 / Build Tools 36.0.0 /
Platform Tools를 준비하세요. 휴대폰에서 **개발자 옵션 → USB 디버깅**을 켜고,
USB 데이터 케이블로 연결한 뒤 신뢰하는 PC의 디버깅 허용 창을 승인합니다.

저장소 루트에서 실행합니다. `adb`가 PATH에 없으면 SDK의 `platform-tools/adb`
경로를 사용하세요. Python 도구는 `JAVA_HOME`과 `ANDROID_SDK_ROOT`를 읽으며,
macOS에서는 Android Studio JDK와 기본 Android SDK 경로를 사용합니다.
그 외 환경에서는 두 변수를 직접 설정하세요. Windows 실행은 아직 검증하지 않았습니다.

```sh
adb devices -l
adb shell getprop ro.product.model
python3 tools/fold-system.py run --seconds 600 --early
```

`run`이 빌드와 테스트를 수행하고 DEX를 전송한 뒤 실행합니다. APK 설치는 없지만,
기기의 `/data/local/tmp`에 실행 파일과 잠금 파일은 생성됩니다. 지원 기기는 위에
명시한 **SM-F966N**이며, 여러 기기가 연결되었다면 ADB에는 `-s SERIAL`,
Python 명령에는 `--serial SERIAL`을 추가하세요.

`READY`가 나오면 커버·내부 화면·켜진 잠금 화면에서 천천히 접었다 펼쳐보세요.
1.5초 멈추면 블러가 부드럽게 사라집니다. `--early`는 움직임 시작을 추정하는
옵션입니다. 이를 빼면 공개 각도 센서만 사용하므로 시작이 늦을 수 있습니다.

- 시험 중에는 실행 터미널과 USB 연결을 유지하세요. 연결을 끊은 뒤의 지속 동작은 보장하지 않습니다.
- **앱 방식이 켜져 있다면 먼저 앱이나 알림에서 효과를 끄세요.** 두 방식은 동시에 실행하지 않습니다.
- 시간을 바꾸려면 `--seconds`에 1–3600을 지정합니다. 재부팅 후 자동으로 실행되지는 않습니다.
- 화면 캡처·저장·업로드는 없습니다. 각도 추정과 배터리 측정의 한계는 위 지원 범위를 참고하세요.

10분 전에 끝내려면 다른 터미널에서 실행합니다.

```sh
python3 tools/fold-system.py stop
```

이 명령은 **ADB 임시 엔진만** 중지합니다. Shizuku 앱 엔진은 앱이나 알림에서 끕니다.
Ctrl+C나 USB 분리만으로 종료됐다고 판단하지 말고, 필요하면 다시 연결해 위 명령으로
중지하세요. 자동 종료 후 전송된 DEX 파일은 남을 수 있지만 자동 실행되지는 않습니다.

상시 사용과 강도 조절 UI가 필요하면 아래 앱 방식을 사용하세요.
에이전트가 실행을 도울 때의 절차와 사용자 안내 문구는 [AGENTS.md](AGENTS.md)에 있습니다.

## 설치와 실행

1. [공식 Shizuku](https://shizuku.rikka.app/download/)를 설치합니다. 권장 버전은 13.6 이상입니다.
2. Shizuku 안내에 따라 **무선 디버깅으로 페어링·시작**하거나 PC의 ADB로 시작합니다.
3. 아래 방법으로 APK를 빌드해 설치하고 **Fold Transition**을 엽니다.
4. **효과 켜기**를 누르고 Shizuku 권한과 실행 상태 알림을 허용합니다.
5. 접었다 펼쳐 효과를 확인합니다. **효과 끄기** 또는 알림의 **끄기**로 중지합니다.

현재 앱 UI는 한국어입니다. 루팅은 필요하지 않습니다. Shizuku는 **shell UID 2000**으로 실행해야 합니다.
구버전 ADB 시험이 실행 중이면 `python3 tools/fold-system.py stop`으로 먼저 종료하세요.
Shizuku를 중지하면 블러 엔진도 종료됩니다.

## USB 없이 동작하는지 확인하기

설정 후 Shizuku 앱의 **무선 디버깅으로 시작**으로 실행하고, Fold Transition에서
효과를 켜세요. Wi-Fi를 켜둔 채 USB를 뽑고 실제로 접었다 펼쳐봅니다.
앱 프로세스 종료 후 복구 시험만으로 케이블 분리 후 유지까지 검증되지는 않습니다.

효과가 멈추면 먼저 Shizuku가 **실행 중**인지 확인하세요. Shizuku 서버가 종료되면
블러 엔진도 종료되며, 앱만으로 shell 권한을 다시 얻을 수는 없습니다.
Shizuku를 무선으로 다시 실행한 뒤 효과의 켜짐 상태를 확인하세요.
Shizuku가 살아 있다면 Fold Transition의 실행 상태나 오류를 확인합니다.

[공식 문제 해결 안내](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging-start-by-connecting-to-a-computer-shizuku-randomly-stops)는
백그라운드 실행 허용, 개발자 옵션·USB 디버깅 유지,
**기본 USB 구성 → 데이터 전송 안 함**도 권장합니다.
시험 기기에서 USB 분리와 함께 Shizuku가 종료된 사례가 있으므로, PC로 시작한
세션이 케이블 분리 후에도 유지된다고 검증 없이 가정하지 마세요.

## 재부팅 후 복구

앱은 켜짐 상태와 강도를 저장합니다. 부팅 또는 Shizuku Binder 재연결 시,
권한이 살아 있으면 엔진을 다시 연결합니다. 앱의 foreground service는 상태를
주기적으로 확인합니다. 반복 오류는 무한 재시작하지 않고 앱에 표시합니다.

**Shizuku 자체가 먼저 실행되어야 합니다.** Shizuku 13.6은 Android 13 이상에서
신뢰하는 Wi-Fi에 연결되었을 때 루팅 없는 자동 시작을 지원합니다.
무선 디버깅 페어링을 마친 뒤 다음 권한이 필요합니다.

```sh
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

이 권한으로 Shizuku의 부팅 처리기는 USB/무선 디버깅을 활성화하고 ADB 인증 만료
설정을 변경할 수 있습니다. 직접 구현한 부팅 우회가 아니라 Shizuku의 기능입니다.
해제하려면 위 명령의 `grant`를 `revoke`로 바꾸세요.

Wi-Fi가 없거나 Shizuku 권한이 해제되었거나 앱이 강제 중지된 경우 자동 복구를
보장하지 않습니다. 앱과 Shizuku를 다시 열어 상태를 확인하세요. 기기 재부팅에 걸친
완전 자동 복구는 별도 실기 검증이 필요합니다.

`CERTIFICATE_UNKNOWN` 오류가 나면 먼저 Shizuku에서 **페어링**을 시작하고,
설정의 **페어링 코드로 기기 페어링** 창을 연 채 Shizuku 알림에 6자리 코드를
입력하세요. `Searching for pairing`은 코드 창을 찾는 대기 상태입니다.
페어링 성공 후 **시작**을 누릅니다. PC를 ADB에 연결한 것만으로 Shizuku의
무선 인증 키까지 등록되지는 않습니다.

참고: [Shizuku 13.6 릴리스](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0),
[공식 부팅 처리 코드](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt).

## 빌드와 검증

JDK 17 이상, Android SDK Platform 36, Build Tools 36.0.0이 필요합니다.
Android Studio의 JDK를 사용해도 됩니다. SDK 경로를 `local.properties`의 `sdk.dir`
또는 `ANDROID_HOME`에 지정하세요.

```sh
./gradlew :app:assembleDebug :app:lintDebug
python3 tools/fold-system.py build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Python 도구는 `JAVA_HOME`, `ANDROID_SDK_ROOT` 환경변수를 지원합니다.
macOS에서는 Android Studio와 기본 Android SDK 경로를 사용합니다.
Debug APK는 개발용 서명입니다. 배포용 release APK는 별도 서명이 필요합니다.

## 구성

| 경로 | 역할 |
| --- | --- |
| `app/` | 설정 UI, Shizuku 권한·연결, foreground guardian, 부팅 복구 |
| `system/` | 공유 compositor 엔진, 접힘 상태 머신, 그라디언트, JVM 테스트 |
| `tools/` | 독립 ADB 시험과 센서 진단 |
| `legacy/` | 빌드에서 제외된 초기 화면 캡처 프로토타입 |
| `docs/` | 기기 조사와 구현 기록 |

앱에는 인터넷·화면 캡처·접근성 권한을 요구하는 실행 흐름이나 분석 SDK가 없습니다.
센서 진단은 기기 내부에서만 처리하며 화면 콘텐츠를 저장하거나 업로드하지 않습니다.

## License

[MIT](LICENSE) · Copyright © 2026 keepYaoung.
Shizuku와 Android/Gradle 의존성은 각 프로젝트의 라이선스를 따릅니다.
