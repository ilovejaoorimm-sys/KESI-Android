# KESI 3.0 검증 — 2026-11-11

## 완료한 검증
- 로컬 APK 빌드 성공: JDK 17 / Gradle 8.9 / Android SDK 35.
- Android/JVM 단위 테스트 29개 통과, 실패 0개.
- Python 수집기 단위 테스트 12개 통과, 실패 0개.
- Android lint 빌드 통과, 오류 없음. 기존 백업 속성·초기 문자열 표기 경고는 존재.
- APK Signature Scheme v2 서명 검증 성공.
- 실제 ECOS API → Python 수집기 → 생성된 JSON → Android와 동일한 Java RemoteFeed 파서 연결 검증 성공.
- 실수집 결과: 6개 정의 중 5개 유효, PF 미수집. 종합 score=null, 판정 보류.
- 7번째 지표 추가 및 가중치 변경을 새 APK 컴파일 없이 처리하는 데이터 모델 테스트 통과.
- 참고용(weight 0) 결측은 종합 산출에 영향을 주지 않는 테스트 통과.
- 오래된 feed, 잘못된 가중치 합, 중복 ID, 미래 날짜, 잘못된 3개월 비교, 미지원 계산 규칙 거부 테스트 통과.

## 아직 완료되지 않은 검증/배포
- GitHub 업로드: 연결 앱 쓰기 API가 403 Resource not accessible by integration 반환. 브라우저는 로그아웃 상태.
- GitHub Actions 원격 빌드 및 정기 수집 실행: 아직 미실시.
- 공개 raw feed URL을 통한 앱 수신: 아직 미실시. 로컬 생성 JSON과 실제 API를 각각 검증함.
- 휴대폰 또는 Android 에뮬레이터 설치/UI 실행: 미실시.

이 APK는 로컬 빌드이며, GitHub에 원격 파일을 배포하기 전까지 원격 지표 추가 기능은 활성화되지 않는다.
배포 전에는 내장 기본 공식 수집기가 동작하며 원격 연결 상태를 화면에 표시한다.
다른 환경에서 생성한 debug 서명과 다르면 기존 앱 위에 설치되지 않을 수 있다. 기존 설치를 지우면 저장된 캐시와 위젯 배치가 사라질 수 있다.
