# 상환관리 브라우저 검증 — 2026-10-05 (Asia/Seoul)

검증 대상: backend feat/#120의 작업 트리와 현재 frontend 작업 트리. 기존 테스트 코드는 재실행하지 않았다. 프런트엔드 TypeScript 컴파일(tsc -b)은 종료 코드 0이었다. 제품 소스와 DB 상환 기록을 변경하지 않았다.

## 실제 사이트 / 실제 백엔드

- localhost:5173에서 사용자가 로그인한 계정의 이번 달 상환액은 100,000원, 이전 달 미상환액은 0원, 전체 채무 잔여액은 600,000원이었다. 계약 99158의 차용자 양예은5에 해당하는 데이터이다.
- 초기 화면은 cooldown 기본 안내였다. 수동 재조회 후 실제 Gemini 분석이 성공했고 '새 분석'으로 표시되었다.
- 실제 대시보드를 새로고침하자 '저장된 분석 재사용'으로 전환되었으며 표시 시각은 10월 5일 03:33으로 유지되었다.
- 동일 소스를 별도 Vite 포트 5175에서 실행하고 실제 localhost:8080 응답의 metadata만 기록하는 임시 로컬 프록시로 응답을 확인했다. 인증 정보는 기록하지 않았다.
- 첫 기록: HTTP 200, delivery=CACHE, reused=true, analyzedAt=2026-10-04T18:33:25.995006300Z, checkedAt=2026-10-04T18:38:01.669280200Z.
- 재조회 기록: HTTP 200, delivery=CACHE, reused=true, analyzedAt=2026-10-04T18:33:25.995006300Z, checkedAt=2026-10-04T18:39:01.114272600Z.
- 거래내역 동기화 버튼을 실제로 실행했다. 동기화 시각이 03:34로 갱신되었고 확인할 거래는 0건이었다. 대시보드 복귀 후 같은 금액과 캐시가 표시되었다.
- 새 상환 거래가 없어 금액 감소 및 변경된 데이터의 새 AI 생성은 실제 데이터로 검증하지 못했다. 실제 송금이나 임의 상환 기록 추가는 실행하지 않았다.
- 사용자가 다른 계정으로 로그인한 뒤 다시 확인했다. 이번 달 420,000원, 이전 달 80,000원, 총 관리 필요액 500,000원, 채무 잔여액 3,420,000원이 표시되었다. 03:47 거래 동기화 완료 후에도 확인이 필요한 거래는 0건이었다. 대시보드 복귀 시 금액은 같고 기존 03:29 분석 재사용이 표시되었다. 이 계정에서도 새 상환 반영 검증에 사용할 거래를 찾지 못했다.
- 기존 로그인 실패 로그 외에는 검증 중 새 브라우저 콘솔 오류를 발견하지 못했다.

## 장애 응답을 재현한 실제 카드 컴포넌트

원본 RepaymentManagementCard를 별도 브라우저 페이지에 렌더링했고 API 장애 응답만 임시 로컬 서버에서 제공했다. 실제 AI/Redis 서버를 중단한 검증은 아니다.

- ANALYSIS_IN_PROGRESS: 요청 시각(UTC) 18:29:22.051, 18:29:27.066, 18:29:32.085, 18:29:37.110. 최초 1회 + 자동 재조회 3회, 이후 중단 안내가 표시되었다.
- AI_UNAVAILABLE: 최초 1회만 요청. 기본 안내를 유지했고 추가 자동 요청이 없었다.
- REDIS_UNAVAILABLE: 최초 1회만 요청. 기본 안내를 유지했고 추가 자동 요청이 없었다.
- COOLDOWN: retryAfterSeconds=30인 응답을 제공했지만 최초 1회만 요청했고 자동 재조회가 없었다.
- 페이지 이동: ANALYSIS_IN_PROGRESS의 최초 응답 뒤 컴포넌트를 unmount했다. 18:34:06.658의 요청 1회 이후 대기 간격이 지난 뒤에도 추가 요청이 없었다.
- WAIT_TIMEOUT: 18:40:56.045, 18:41:01.077, 18:41:06.096, 18:41:11.117의 총 4회 요청 뒤 중단 안내가 표시되었다.
- 재조회 상한은 한 조회 흐름 기준이다. 수동 조회와 focus/visibility 또는 변경 이벤트는 새 흐름을 시작하므로 횟수가 다시 초기화된다.

## 운영 설정 판정

- RepaymentGeminiClientConfig의 코드 기본값은 PT15S, SDK attempts=1이다.
- application-dev.yml은 ai-request-timeout=PT15S, spring.ai.retry.max-attempts=0을 설정한다.
- application-prod.yml에는 Gemini provider/API key/model 설정 및 Spring AI 재시도 제한이 없다. 배포 환경에서 별도 주입하지 않는다면 운영에서 같은 설정을 보장할 수 없다. 배포 서버와 실제 프로필 환경변수에는 접근하지 못했다.
- application-dev.yml과 application-prod.yml은 .gitignore 대상이다. 로컬 YAML 수정만으로 Git 배포에 반영되지는 않는다.
- RepaymentGeminiClientConfig.java는 검증 시점에 untracked 상태였다. 커밋에 포함되지 않으면 Git 기반 배포에 누락될 수 있다.

운영 환경에서 명시적으로 확인할 변수: SPRING_AI_MODEL_CHAT, SPRING_AI_GOOGLE_GENAI_API_KEY, SPRING_AI_GOOGLE_GENAI_CHAT_MODEL, SPRING_AI_RETRY_MAX_ATTEMPTS=0, SAI_REPAYMENT_AI_REQUEST_TIMEOUT=PT15S 및 Redis 연결 설정.

## 결론

캐시 metadata와 프런트엔드의 제한적 재조회 및 unmount 시 정리는 확인했다. 실제 상환 데이터 변화에 따른 새 분석은 테스트 거래 부재로 미확인이다. 운영 프로필 및 배포 환경은 추가 설정 확인이 필요하며 전체 완료로 판정하지 않는다.
