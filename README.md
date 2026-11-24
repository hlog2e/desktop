# AhaKey Studio 한국어판

AhaKey-X1(Vibecoding Keyboard)을 설정하는 프로그램 [AhaKey Studio](https://github.com/AhakeyAI/desktop)의 한국어 포크예요. Windows 앱의 모든 화면을 한국어로 옮겼고, 블루투스 연결이 끊길 때 원인을 볼 수 있는 연결 기록 창을 더했어요.

## 내려받기

| 운영체제 | 파일 | 설명 |
|---|---|---|
| Windows 10/11 (64비트) | [**설치 파일 (.exe)**](https://github.com/hlog2e/desktop/releases/latest/download/AhaKey-Studio-Korean-Setup.exe) | 설치하면 시작 메뉴와 바탕화면에 바로 가기가 생겨요 |
| Windows 10/11 (64비트) | [포터블 (.zip)](https://github.com/hlog2e/desktop/releases/latest/download/AhaKey-Studio-Korean-Windows-Portable.zip) | 설치 없이 압축을 풀고 `AhaKeyStudioKR.exe`를 실행해요 |
| macOS 13 이상 | [맥 앱 (.dmg)](https://github.com/hlog2e/desktop/releases/latest/download/AhaKey-Studio-macOS.dmg) | 원본 맥 앱을 그대로 빌드한 것이라 화면은 영어와 중국어예요 |

모든 버전은 [릴리스 페이지](https://github.com/hlog2e/desktop/releases)에 있어요. Java와 BLE 드라이버가 함께 들어 있어서 따로 설치할 것은 없어요.

## Windows에서 쓰는 법

1. 설치 파일을 실행해요. 게시자 서명이 없는 개인 빌드라 "Windows의 PC 보호" 창이 뜨면 **추가 정보 → 실행**을 눌러요.
2. AhaKey Studio를 열어요. 처음부터 한국어로 열려요. 다른 언어는 **더 보기 → 언어 / Language**에서 바꿀 수 있어요.
3. 키보드를 연결해요.
   - **USB 케이블**: 꽂으면 바로 연결돼요.
   - **블루투스**: Windows 설정 → Bluetooth 및 장치에서 키보드를 먼저 페어링해요. 그다음 앱 위쪽의 **BLE 드라이버** 칸을 눌러 BLE 드라이버 창을 열고, 목록에서 키보드를 선택해 연결해요. 한 번 연결하면 다음부터는 저절로 연결돼요. BLE 드라이버 칸을 5초 동안 누르고 있으면 드라이버가 꺼져요.
4. 키 설정, 조명, OLED 화면, 승인 스위치를 바꾼 뒤 **설정 저장**을 눌러 키보드에 저장해요.

### 연결이 자꾸 끊길 때

**더 보기 → 연결 기록**을 열면 연결과 관련된 일이 시간순으로 보여요.

- **보기**를 `원본 데이터까지`로 바꾸면 키보드와 주고받은 데이터(16진수)까지 나와요. 줄을 선택하면 아래에 자세한 내용이 보여요.
- `앱`, `BLE 드라이버`, `주고받은 데이터`로 출처를 고르고, 검색창에 `끊`, `0x9F`처럼 넣어 찾을 수 있어요.
- 기록은 날짜별 파일로도 남아요(`%LOCALAPPDATA%\AhaKeyStudio\logs`). **기록 폴더 열기**로 지난 기록을 볼 수 있고, **파일로 저장**으로 보이는 기록만 따로 저장할 수 있어요.

## 원본과 다른 점

- Windows 앱의 화면, 알림, 오류 문구, 설치 프로그램, BLE 드라이버 창이 한국어예요. 글꼴은 맑은 고딕을 써요.
- 연결 기록 창과 BLE 드라이버의 기록 파일을 더했어요.
- 업데이트 확인은 이 저장소의 릴리스를 봐요. 공식 영어판으로 자동 업데이트해서 한국어판이 덮어써지는 일은 없어요.
- 공식 앱과 따로 설치돼요(`AhaKeyStudioKR` 폴더). 둘을 함께 켜면 키보드 연결이 서로 부딪칠 수 있으니 하나만 켜 두세요.

## 안 되는 것

- **펌웨어 업데이트**: 펌웨어 파일과 WCH 굽기 도구(WCHISP)가 공개되지 않아 들어 있지 않아요. 펌웨어는 키보드에 저장되니 업데이트할 때만 [공식 앱](https://ahakey.com)을 쓰면 돼요.
- **키보드 OLED 화면의 한글**: 키보드 펌웨어가 영문, 숫자, 기호만 표시해요. 키 설명은 영어로 적어요.
- **로컬 음성 인식의 한국어**: 내려받는 음성 모델이 중국어와 영어용이에요. 한국어 받아쓰기는 음성 키 동작을 `Windows 음성 입력(Win+H)`으로 두면 돼요.
- **클라우드 계정과 AhaType**: 원래 서비스(중국 서버, 위챗 결제)를 그대로 써요.

## 직접 빌드하기

GitHub Actions(`.github/workflows/korean-build.yml`)가 `ko-v<버전>` 태그를 올릴 때마다 Windows 설치 파일, 포터블, 맥 앱을 만들어 릴리스에 올려요. 직접 빌드하려면 Windows에서 JavaFX가 들어 있는 JDK 17(Liberica Full), Maven, Visual Studio Build Tools, WiX 3이 필요해요.

```powershell
msbuild BLE_tcp_bridge\BLE_tcp_driver.csproj /restore /p:Configuration=Release
cd ahakeyconfig-win-java
mvn clean package
.\build-korean-windows.ps1 -JdkHome $env:JAVA_HOME -WixBin "C:\Program Files (x86)\WiX Toolset v3.14\bin"
```

번역 기준과 용어집은 [docs/ko/translation.md](docs/ko/translation.md)에 있어요. 원본 저장소의 README는 [docs/upstream-README.md](docs/upstream-README.md)에 옮겨 두었어요.

## 라이선스

원본과 같은 [Apache-2.0](LICENSE)이에요. 원본: [AhakeyAI/desktop](https://github.com/AhakeyAI/desktop)
