# 대량 배치 부하 시나리오

실행 스크립트는 `scripts/retry-batch.js`, `scripts/notification-batch.js`이다. `batch-common.js`는 공통 Job 실행·측정 모듈이다. 실제 부하 실행 전 격리된 MariaDB와 앱 dev 프로필을 준비한다. 자동 스케줄러 및 다른 동기화/매칭 Job은 테스트 동안 중지한다. Job은 fixture 계좌뿐 아니라 서비스 전체 대상에 실행되므로 운영 DB에서 사용하지 않는다.

## 공통 준비와 완료 시간

1. `seed/01_cleanup.sql` → `02_seed.sql` → `03_matching_bulk.sql`을 실행한다. SQL은 MariaDB SEQUENCE 테이블을 사용한다. 기본 거래는 30,000건이다. 규모 변경은 03의 `@mlt_users`, `@mlt_trades`로 한다.
2. 아래 두 시나리오는 각각 위 초기화부터 시작한다. 기대값 테이블은 실행 전 스냅샷이며, 재실행 사이에 SQL seed를 다시 적용하면 안 된다.
3. `BASE_DATE`를 명시한다. 알림 seed가 출력한 날짜와 같아야 한다. 날짜 기본값은 KST 오늘이다.
4. dev 트리거는 `POST /dev/batch/{jobName}?baseDate=...`이고 호출마다 새 `runId`를 만든다. k6는 단일 VU로 Job을 순차 실행한다. 인증이 필요한 환경은 `ACCESS_TOKEN`을 전달한다.
5. `batch_completion_ms{job,run}`은 **COMPLETED 응답까지의 HTTP 시간**이다. 시작/종료 시간의 차이인 정확한 서버 Job 시간은 `06_verify_batch_executions.sql`로 확인한다. 비동기 launcher가 STARTING/STARTED를 반환하면 스크립트는 실패하며 그 응답 시간을 완료 시간으로 기록하지 않는다. 현재 트리거에는 실행 상태 조회 API가 없어 비동기 launcher의 완료 polling은 지원하지 않는다.
6. `06_verify_batch_executions.sql` 실행 전 같은 DB 세션에서 `@base_date`, `@started_after`를 지정한다. 실행 중인 Job, FAILED, skip 건수 및 exit_message를 확인한다. 테스트 실행 횟수도 retry는 ROUNDS회, 알림은 Job별 2회와 일치해야 한다. BATCH 테이블 대소문자/스키마는 실제 Spring Batch 저장소에 맞춘다.

`batch_read_items`/`batch_write_items`는 Step item 수이다. 재시도 Job에서는 **계좌 수**이며 성공 거래 수가 아니다. 리마인더 Job에서는 이미 존재하는 알림을 건너뛰어도 processed가 증가하므로 두 번째 writeCount가 0일 필요는 없다. DB 검증 SQL로 신규 알림 수와 중복을 판단한다. 기존 02 데이터도 Job 대상이므로 Job 전체 건수와 MLT fixture 건수를 구분한다.

## 실패·미매칭 재시도

초기화 후 `04_retry_batch.sql`을 실행한다. 기본 설정에서 성공 복구 대상은 7,500건, 5xx 지속 실패 대상은 2,500건이다.

| 그룹 | 시작 상태 | 기대 결과 |
| --- | --- | --- |
| recover | FAILED 또는 UNMATCHED, retry=0 | 유일 후보를 적용해 APPLIED, retry=1 |
| persistent_failed | FAILED, retry=0 | 테스트용 5xx 주입으로 FAILED 유지, 최대 5회 및 소진 알림 |
| failed_to_unmatched | FAILED, retry=0 | 오류가 해소되어도 후보가 없어 UNMATCHED, 이후 최대 14회 |
| persistent | UNMATCHED, retry=0 | 계속 UNMATCHED, 14회 뒤 재처리 제외 |
| boundary | UNMATCHED, retry=13 | 첫 실행에 14회 도달·소진 알림, 이후 변화 없음 |
| exhausted | FAILED=5 또는 UNMATCHED=14 | 재처리 및 소진 알림 재발송 없음 |

```powershell
docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e BASE_DATE=2026-10-19 /scripts/retry-batch.js
```

기본 `ROUNDS=15`는 UNMATCHED 한도 14회 이후 추가 실행까지 포함한다. 최종 상태·재시도 증가량·누락은 `04_verify_retry_batch.sql`로 검증한다. 회차 변경 시 검증 SQL의 `@rounds`도 맞춘다. 위반 쿼리는 모두 0행이어야 한다. 그룹별 건수 조회와 알림 대상 ID 조회는 보고용이다. fixture 기대값 테이블이 비어 있지 않은지 반드시 확인한다.

상태별 한도는 FAILED=5, UNMATCHED/NEEDS_CHECK=14이다. FAILED가 UNMATCHED로 바뀌면 5회가 아니라 새 상태의 14회 한도를 적용한다. 이미 한도에 도달한 거래는 Job에서 조회되지 않으므로 이 실행에서 소진 알림이 새로 발생하지 않는다.

오류 로그/계좌 skip 경로도 재현하려면 04의 `@fault_accounts`를 1~10으로 지정한다. 마지막 seed 사용자 계좌에 금액 0의 잘못된 거래를 만들어 모델 검증 오류를 유발한다. 재시도 계좌 처리 롤백으로 FAILED/retry=0이 유지되고 `[BATCH SKIP - PROCESS]`가 기록되어야 한다. 정상 그룹과 계좌를 분리해 성공 그룹이 함께 롤백되지 않게 했다. 이 경로는 계좌 예외의 지속 실패이며 **거래별 FAILED 상태가 5회까지 증가하는 경로와 다르다**.

거래별 지속 FAILED 경로를 위해 `support/java/.../RetryLoadtestFaultConfig.java`를 추가했다. 일반 소스 경로 밖에 있어 평소 빌드에는 포함되지 않는다. 아래 opt-in init 스크립트로 로컬 앱을 실행하고 `dev,loadtest-faults` 프로필과 활성화 속성을 모두 지정한다.

```powershell
.\gradlew.bat -I k6-loadtest/support/loadtest-faults.init.gradle bootRun --args="--spring.profiles.active=dev,loadtest-faults --loadtest.retry-faults.enabled=true"
```

이 설정은 자동 납부 호출의 거래 memo가 `BRT:persistent-failed`인 경우에만 `PAYMENT_STATUS_UPDATE_FAILED` 5xx DomainException을 던진다. 납부 트랜잭션에 들어가기 전에 오류를 발생시켜 retry_count 증가와 FAILED 결과는 정상 커밋되고, 납부 기록은 생성되지 않는다. 지정된 2,500건은 5회까지 실패 후 더 이상 처리되지 않아야 한다. 기본 앱으로 실행하면 해당 그룹이 성공해 SQL 검증이 실패하므로 잘못된 성공 판정을 막는다. fault 설정의 거래 memo 조회 SELECT 비용은 측정에 포함된다. init 스크립트는 테스트 bootRun/컴파일에만 사용하고 배포 빌드에는 사용하지 않는다.

### 소진 알림과 로그 증거

외부 Slack 대신 로컬 수집기를 사용한다. 수집 파일은 실행별 새 파일을 사용한다.

```powershell
node k6-loadtest/tools/batch-evidence.mjs capture k6-loadtest/evidence/webhooks.jsonl 18081
```

테스트 앱의 `slack.batch-notification.enabled=true`, `slack.batch-notification.webhook-url=http://127.0.0.1:18081/webhook`을 지정한다. 앱이 컨테이너 안에 있다면 앱에서 접근 가능한 로컬 수집 주소/바인딩을 별도 설정한다. 기본 수집기는 호스트 loopback만 수신한다. 소진 건마다 동기 HTTP가 발생하므로 이 비용도 Job 시간에 포함된다. 외부 Slack webhook은 사용하지 않는다.

04 검증 SQL의 `expected_exhaustion_notification` 결과를 헤더 없는 거래 ID 목록으로 `evidence/exhaustion-ids.txt`에 저장하고, 같은 실행 구간의 앱 로그를 `evidence/app.log`로 저장한다. Windows PowerShell의 출력 리다이렉션은 UTF-16일 수 있으므로 파일은 UTF-8로 저장한다. 선택 오류 그룹을 사용했다면 해당 거래의 `linked_account_id`를 헤더 없는 `evidence/error-account-ids.txt`에 저장한다.

```powershell
node k6-loadtest/tools/batch-evidence.mjs verify k6-loadtest/evidence/webhooks.jsonl k6-loadtest/evidence/exhaustion-ids.txt k6-loadtest/evidence/app.log
# 오류 계좌 사용 시 마지막 인자로 error-account-ids.txt 추가
```

검증기는 예상 소진 ID별 1회 수신, 예상 밖 소진/중복/누락, 상태별 한도, Job 성공 로그, 실패 Job 로그, 지정 계좌 process-skip 로그를 검사한다. 기대 ID 파일에는 이미 소진된 거래나 성공 전환 거래를 넣지 않는다. 지속 FAILED 그룹은 `Auto matching transaction failed. transactionId=...` 오류 로그가 거래별 5회 있어야 한다. `04_verify_retry_batch.sql`의 persistent_failed ID 목록을 UTF-8 파일로 저장해 검증기의 마지막 인자로 전달하면 이 횟수도 검사한다. 기존 02 데이터에서 추가 소진이 발생하면 예상 밖 알림으로 실패하므로 원인을 조사한다. FAILED 상태/횟수/소진 알림은 SQL 결과와도 대조한다.

## 연체·상환 예정 알림

새로 초기화 후 `05_notification_batch.sql`을 실행한다. 상환 일정과 정산 납부의무 각각에 어제, D-3, D-1, 당일, D-2 제외군, PAID 제외군을 만든다. 같은 사용자가 여러 대상을 보유해 수신자만으로 잘못 중복 제거하는 경우도 검출한다.

```powershell
docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e BASE_DATE=2026-10-19 /scripts/notification-batch.js
```

`repaymentScheduleOverdueJob`, `settlementOverdueJob`, `repaymentDueReminderJob`, `settlementDueReminderJob`을 각각 같은 기준일로 2회 실행한다. 상환 연체 Job은 어제 이전 PENDING을 OVERDUE로 바꾸고, 정산 연체 Job은 납부의무의 overdue_since를 갱신한다. **현재 연체 Job 자체에는 연체 알림 생성 기능이 없다.** 예정 알림은 세 단계별 NotificationType으로 생성된다.

`05_verify_notification_batch.sql`은 모든 fixture의 다음 조건을 검증한다.

- 날짜에 맞는 상환 상태, 정산 overdue_since 및 누락 대상
- D-3/D-1/당일별 올바른 알림 종류와 수신자
- 수신자+종류+대상 ID별 정확히 한 건 (정산은 obligation ID, 상환은 schedule ID)
- D-2·PAID 제외군의 잘못된 발송, 잘못된 secondary_reference_id
- 재실행 후 같은 대상의 알림 수가 증가하지 않음

모든 위반 조회가 0행이고 상태별 보고 건수가 기대값과 같아야 한다. Job별 첫 실행/재실행 시간 및 skip/error는 06 SQL과 앱 로그로 비교한다. 리마인더 `processed`는 실제 삽입 수가 아니므로 최종 알림 저장 건수 조회를 기준으로 판정한다. 이 시나리오는 **순차 재실행**을 검증한다. 현재 중복 방지는 exists 조회 후 insert이므로 동시 Job 경합의 원자적 중복 방지를 보장하는 테스트는 아니다.

## 검증 범위

k6 문법과 모의 HTTP 완료/오류 경로, 로컬 증거 검증기는 개발 환경에서 확인할 수 있다. SQL/Job 부하의 실제 실행 결과는 격리 DB와 앱을 연결해 측정해야 한다. 테스트가 끝나면 `01_cleanup.sql`로 사용자 소속 데이터를 정리하고 `lt_retry_expectation`, `lt_schedule_expectation`, `lt_obligation_expectation` 기대값 테이블은 필요 없을 때 별도로 삭제한다.


장애 주입 프록시 단위 검증:

```powershell
.\gradlew.bat -I k6-loadtest/support/loadtest-faults.init.gradle test --tests org.teamsai.saibackend.loadtest.RetryLoadtestFaultConfigTest --offline
```

지속 FAILED 오류 로그까지 검사하는 전체 증거 검증 예시 (`-`는 선택 계좌 skip 검증 생략):

```powershell
node k6-loadtest/tools/batch-evidence.mjs verify k6-loadtest/evidence/webhooks.jsonl k6-loadtest/evidence/exhaustion-ids.txt k6-loadtest/evidence/app.log - k6-loadtest/evidence/persistent-error-ids.txt
```
