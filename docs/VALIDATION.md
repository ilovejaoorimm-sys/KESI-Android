# 검증 기록 — 2026-11-10 KST

- 로컬 Gradle 빌드: 성공 (Temurin JDK 17, Gradle 8.9, AGP 8.7.3, SDK 35).
- `testDebugUnitTest`: 17 tests, 0 failures, 0 errors.
- `lintDebug`: 0 errors; 3 warnings (구형 Android 백업 속성 및 초기 표시 문자열 대시 표기). 빌드 차단 오류 없음.
- APK `apksigner verify --verbose`: 성공, APK Signature Scheme v2, signer 1명.
- aapt package: com.kesi.widget, versionCode 2, versionName 2.0, minSdk 26, targetSdk 35, 홈 화면 위젯 컴포넌트 존재.
- 실제 폰 설치/에뮬레이터 UI 실행: 미실시. 컴파일/정적 분석/서명 및 패키지 구조를 검증함.
- GitHub 원격 저장소: 조회 가능하나 비어 있음. 연결 앱의 쓰기 작업이 HTTP 403 Resource not accessible by integration으로 거부됨.
- GitHub Actions: 워크플로 구현 완료, 원격 실행은 인증 문제로 아직 미실시. 로컬 APK를 GitHub Actions 산출물로 표현하지 않음.

## 실제 수집 코드 연결 시험
Android 앱과 동일한 OfficialCollector.java를 JVM에서 실행, 2026-11-10 기준 조회.

| 지표 | 최신 관측기간 | 수집값 | 3개월 전 관측기간 | 비교값 |
|---|---|---:|---|---:|
| 은행전체 기업대출, 1개월 이상 연체율 | 2026-08 | 0.8% | 2026-05 | 0.8% |
| 계절조정 실업률 | 2026-09 | 2.6% | 2026-06 | 2.7% |
| 전국 종합 주택매매가격 전년비 | 2026-09 | 3.166496% | 2026-06 | 2.459016% |
| 회사채 AA− 3년 − 국고채 3년 | 2026-11-10 | 0.694%p | 2026-08-10 | 0.698%p |
| 원/달러 매매기준율 | 2026-11-10 | 1355.7원 | 2026-08-10 | 1420.1원 |
| PF | 미수집 | — | — | — |

이 표는 검증 기록일 뿐 앱에 내장하지 않는다. 실행 중 앱은 항상 공식 API에서 직접 조회한다.
위 5개 응답의 발표일 필드는 제공되지 않으므로 모두 미확인 처리했다.
검증 출력: usable=5, score=null, level=판정 보류 · 자료 부족.

## 자동 테스트 범위
결측값·이전 샘플 캐시 거부, 점수 경계와 가중치·가산점, 등급 경계, 과거 관측 결측, stale/통신 실패 제외, JSON null 직렬화, 날짜/항목 검증, 중복 관측 거부, 최신 관측 선택, 과거 조회 실패 시 현재값 보존, PF 대체값 금지.
