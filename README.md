# 쉼표 (NAP MODE)

> 차가 잠들 환경을 만들고, 자는 동안 지켜 주고, 몸으로 천천히 깨워 주는 차량용 휴식 앱

현대자동차그룹 차량용 앱 개발 공모전 출품작 · 팀 **SOLVIX**

- 🎬 시연 영상: (YouTube 링크)
- 📦 APK 받기: [Releases](../../releases)

<!-- 화면 캡처를 docs/screenshots 폴더에 넣으면 아래에 보입니다 -->
| 홈 | 쉬는 중 | 깨어남 |
| --- | --- | --- |
| ![홈](docs/screenshots/home.png) | ![쉬는 중](docs/screenshots/resting.png) | ![깨어남](docs/screenshots/awake.png) |

---

## 주요 기능

| 기능 | 하는 일 |
| --- | --- |
| **쉼표 시그널** | 파킹브레이크·기어로 주차 여부를 자동 확인하고, 쉬는 동안 문·브레이크·배터리를 감시해 멈추거나 일찍 깨웁니다 |
| **쉼표 프리셋** | 짧은 휴식·가벼운 낮잠·충분한 휴식, 1분 단위 조절 (최대 30분) |
| **휴식 환경 제어** | 조명을 낮추고, 온도를 맞추고, 등받이를 눕힙니다. 끝나면 원래대로 되돌립니다 |
| **깨어남 케어** | 끝나기 전부터 조명·온도·등받이로 몸을 먼저 깨우고, 깨어난 뒤 차례로 안내합니다 |
| **대나무숲** | 쉬기 전 고민을 적어 맡깁니다. 기기 안에서만 암호화해 저장합니다 |

그 밖에: 다크 모드, 슬립 모드(쉬는 동안 화면 어둡게), 메모 글씨 크기 조절, 보안 상태 화면

## 안전·보안 원칙
- 시동·잠금 해제·조향·제동·주행 제어는 하지 않습니다. 편의 기능만 정차 중에 제어합니다.
- 고민 글은 Android Keystore 기반 AES-256-GCM으로 암호화해 기기에만 저장합니다.
- 인터넷 권한을 두지 않으며, 백업에서 제외합니다.

## 실행 방법
1. **Android Studio**(최신 버전)로 이 저장소를 엽니다.
2. Pleos Connect 에뮬레이터(v2.1.0, API 34)를 실행합니다.
3. ▶ Run (`app`) — Pleos Vehicle SDK 2.3.2는 Gradle이 Pleos Playground 저장소에서 자동으로 받습니다.
4. 차량 상태는 에뮬레이터의 **Virtual Console**에서 바꿔 볼 수 있습니다 (Door Lock, Brake Pedal, Battery SOC).

> APK만 설치하려면: [Releases](../../releases)에서 받아 `adb install shimpyo-v1.0.apk`

## 기술 스택
Kotlin · Android (minSdk 28, compileSdk 37) · Pleos Vehicle SDK 2.3.2 · Coroutines · Pretendard 글꼴

| 사용한 SDK 함수 | 용도 |
| --- | --- |
| `getEvBatteryStateOfCharge` / `registerEvBatteryStateOfCharge` | 배터리 잔량 읽기·감시 |
| `getDoorLockState` / `registerDoorLockState` | 문 잠금 읽기·감시 |
| `registerBrakePedalStatus` | 브레이크 조작 감시 |
| `check…Capabilities` | 차종별 지원 기능 확인 (공조·조명·시트) |

## 폴더 구조
```
app/src/main/java/com/example/hyundaitest/
├── ui/        화면 (홈, 시작 확인, 휴식, 대나무숲, 설정 …) + 디자인 규칙 ShimpyoDesign.kt
├── nap/       휴식 흐름 (NapController 상태 관리)
├── vehicle/   Pleos SDK 연동 (상태 읽기·감시·환경 제어)
├── safety/    주차·프리셋 규칙
├── security/  암호화 (TextCipher)
└── data/      기록·대나무숲·설정 저장
app/src/test/  단위 테스트
```

## 앞으로 할 일
나만의 모드(이름 붙인 프리셋) · Gleo AI 음성 명령과 음성 메모 · 휴대폰 연동(미리 켜기·메모 공유) · 소리·진동 깨우기

## 라이선스
- 글꼴: Pretendard — SIL Open Font License 1.1 (`app/src/main/assets/licenses/Pretendard-OFL.txt`)
