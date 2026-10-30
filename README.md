# Lucy 오버레이 (Android)

홈 화면·다른 앱 **맨 위**에서 Lucy 캐릭터가 **화면 가장자리**를 따라 돌아다니는 안드로이드 앱입니다.

CYLU 캐릭터: 챠니(CHANI) · 여비(YEOBI) · 워니(WONNI) · 이리(IRI). 비공식 팬 제작 오버레이입니다.

## 기능

- 4명 전체 선택 / 출동 중 **선택 적용**으로 캐릭터 교체
- 가장자리(상·하·좌·우)만 걷기 · 드래그 후 가장 가까운 벽으로 스냅
- 배터리 최적화 제외 요청 · 화면 꺼지면 애니메이션 일시 정지
- 잠금 화면: **불가** (시스템이 잠금 중 앱 오버레이를 숨김). 잠금 해제 후 다시 표시

## 빌드

```bash
cd ~/Desktop/SYSEO/Desktop/code/lucy-overlay
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
