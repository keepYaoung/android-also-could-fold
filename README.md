# Android Also Could Fold

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
초기 움직임은 `dumpsys sensorservice`의 vendor 이벤트 시각으로 추정하며,
숨겨진 연속 각도 값을 읽는 방식은 아닙니다. 아주 느린 움직임이나 작은 역회전은
구분하지 못할 수 있습니다. 켜진 화면에서 4Hz 진단 폴링을 사용하며 장시간 배터리
영향은 아직 측정하지 않았습니다.

## 설치와 실행

1. [공식 Shizuku](https://shizuku.rikka.app/download/)를 설치합니다. 권장 버전은 13.6 이상입니다.
2. Shizuku 안내에 따라 **무선 디버깅으로 페어링·시작**하거나 PC의 ADB로 시작합니다.
3. 아래 방법으로 APK를 빌드해 설치하고 **Fold Transition**을 엽니다.
4. **효과 켜기**를 누르고 Shizuku 권한과 실행 상태 알림을 허용합니다.
5. 접었다 펼쳐 효과를 확인합니다. **효과 끄기** 또는 알림의 **끄기**로 중지합니다.

루팅은 필요하지 않습니다. Shizuku는 **shell UID 2000**으로 실행해야 합니다.
구버전 ADB 시험이 실행 중이면 `python3 tools/fold-system.py stop`으로 먼저 종료하세요.
Shizuku를 중지하면 블러 엔진도 종료됩니다.

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
