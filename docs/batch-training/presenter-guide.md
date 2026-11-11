# SAI 배치 발표자 가이드

2026-10-27 로컬 소스 기준. 발표용 `batch-explained.html`을 옆에 두고 읽는 상세 해설입니다. 구현 방식과 조건은 코드에서 확인한 사실이고, “이 방식을 선택한 이유”는 구조에 근거한 해석입니다. 선택 이유는 현재 JobConfig와 서비스의 책임 분담을 근거로 설명합니다.

## 1. 자료 사용 방법과 발표 순서

HTML을 브라우저로 열면 목차로 이동하고 실제 코드 카드를 펼칠 수 있습니다. 발표 모드는 왼쪽 목차를 숨겨 화면을 넓힙니다. 인쇄 버튼으로 PDF를 저장할 수 있으며, 기본적으로 접힌 코드는 인쇄하지 않습니다. 코드를 포함하려면 먼저 모두 펼치십시오. 문서가 길기 때문에 발표에서는 모든 코드를 읽기보다 각 그림의 입력·핵심 판단·결과만 짚는 편이 좋습니다.

권장 발표 시간은 약 25~30분입니다. 개념과 전체 구조 7분, 6개 흐름 14분, 공통 인프라와 운영 주의점 5분, 질문 4분을 기준으로 조절하십시오. 10분 발표라면 WHY, 전체 아키텍처, Chunk/Tasklet, 6개 흐름의 한 문장, 운영 포인트만 선택합니다.

| HTML 화면 | 설명 목표 | 발표할 문장 |
|---|---|---|
| 01 WHY | 배치 필요성 | 사용자가 접속하지 않아도 시간이 지나면 해야 할 일을 처리합니다. |
| 02 CONCEPTS | Job과 Step 구분 | Job은 업무 단위, Step은 그 안에서 실행하는 단계입니다. |
| 03 ARCHITECTURE | 계층과 진입점 | Scheduler가 시점을, Batch가 실행을, Service가 업무를 담당합니다. |
| 04 PROCESSING | 방식 차이 | 항목 반복을 프레임워크가 관리하면 Chunk, 업무 절차를 서비스가 관리하면 현재의 Tasklet 구조입니다. |
| 05 DESIGN CHOICES | 10개 방식 매핑 | 계좌별 반복 2개는 Chunk이고 나머지 업무 절차 8개는 Tasklet입니다. |
| 06~11 FLOW | 업무 이해 | 각 그림에서 대상, 조건, 바뀌는 데이터를 순서대로 말합니다. |
| 12 INFRASTRUCTURE | 공통화 이유 | 업무별 차이는 서비스에, 운영의 일관성은 공통 인프라에 둡니다. |
| 13 TAKEAWAYS | 오해 방지 | COMPLETED와 모든 건의 성공은 같지 않고, 처리 건수의 단위도 다릅니다. |

## 2. 배치는 왜 필요한가

일반적인 API는 사용자의 요청을 계기로 움직입니다. 계약을 생성하거나 정산에 참여하는 일은 사용자의 요청으로 시작할 수 있습니다. 그러나 “내일이 납부일”, “오늘부터 연체”, “30일 이상 남은 미납”은 사용자가 버튼을 누르지 않아도 발생하는 사실입니다. 이것을 화면 조회 때만 갱신하면 접속하지 않는 사람의 데이터는 계속 과거 상태로 남습니다.

그래서 주기적으로 데이터를 조회해 현재 기준일에 맞는 후속 처리를 수행합니다. 계좌가 많을 때 은행 조회와 재매칭을 한 사용자 요청에서 모두 처리하지 않고 정기적으로 보정할 수 있다는 점도 중요합니다. 이 프로젝트의 정기 스케줄은 하루 한 번이며, 사용자 요청을 통한 거래 동기화 경로도 별도로 제공됩니다.

Scheduler와 Batch는 다릅니다. Scheduler는 “언제 실행하나”를 해결합니다. Batch는 “무엇을 어떤 단위와 실패 정책으로 실행하고 결과를 어디에 기록하나”를 해결합니다. 현재 코드는 @Scheduled로 시점을 정하고 JobOperator.start()로 Spring Batch Job을 시작하며, dev 컨트롤러도 같은 Job을 수동 실행합니다.

## 3. 반드시 이해해야 할 기본 용어

### Job과 Step

은행거래 동기화라는 업무 이름이 `transactionSyncJob`입니다. 그 안에서 계좌를 읽고 동기화하는 실행 단계가 `transactionSyncStep`입니다. 하나의 Job은 여러 Step을 가질 수 있지만 현재의 10개 Job은 모두 Step 하나를 연결합니다. 자료의 6개 기능군은 10개 Job을 업무 목적에 따라 묶은 설명 단위입니다.

### JobInstance와 JobExecution

Job은 정의이고 JobInstance는 파라미터로 구별하는 논리적 작업입니다. JobExecution은 그 작업의 실제 실행 시도와 결과입니다. StepExecution은 그 안의 Step 실행 결과입니다. 기본적으로 식별 JobParameters가 JobInstance의 구분에 관여합니다.

현재 코드는 `baseDate`와 `runId`를 전달하며 runId를 현재 밀리초로 생성합니다. 따라서 같은 baseDate로 다시 실행해도 새 JobInstance로 구별될 수 있습니다. 기존 실패 실행을 프레임워크의 restart로 이어가는 것과, 새 runId로 업무를 다시 수행하는 것은 구분해야 합니다. 이 프로젝트에 “항상 실패 지점부터 자동 재개된다”고 말하지 마십시오.

### JobRepository

업무 DB에는 계좌, 거래, 정산, 결제의무, 상환 스케줄 등이 저장됩니다. JobRepository는 배치 실행 상태·시각·파라미터·Step 집계·실행 컨텍스트 같은 메타데이터를 관리합니다. 같은 물리 DB를 사용하더라도 목적이 다릅니다. `BatchInfraConfig`의 `@EnableJdbcJobRepository`가 JDBC 기반 저장소를 활성화합니다.

## 4. Chunk와 Tasklet을 정확히 설명하는 방법

### Reader / Processor / Writer를 DTO의 이동으로 설명하기

Reader는 처리할 항목을 공급합니다. `LinkedAccountSyncTargetReaderFactory.create()`는 `LinkedBankAccount`에서 연결 상태가 AVAILABLE인 계좌를 ID 순서로 조회합니다. 조회 결과는 `LinkedAccountSyncTargetDTO(linkedAccountId, userId)`입니다. Reader에 들어가는 핵심 설정은 조회 쿼리, AVAILABLE 파라미터, 정렬, pageSize 50입니다. Step은 이 DTO를 하나씩 받습니다.

Processor는 DTO 하나를 받아 그 계좌의 업무를 수행합니다. 동기화 Job의 Processor는 `syncAndMatch(target.userId(), target.linkedAccountId(), true)`를 호출합니다. 그 서비스는 거래 조회·저장 이후 자동 매칭을 실행합니다. Processor는 처리 후 `return target`으로 동일한 계좌 DTO를 반환합니다. 재시도 Job의 Processor는 `retryForAccount()`를 호출하고 같은 DTO를 반환합니다. 따라서 두 Processor에서 중요한 내용은 계좌별 도메인 서비스 호출입니다.

Writer는 Processor가 반환한 DTO 묶음을 받습니다. 현재 `.chunk(50)`이므로 일반적으로 최대 50개 계좌 DTO가 Writer에 전달됩니다. `noOpWriter()`는 `chunk.size()`와 계좌 ID 목록 등 완료 로그를 출력합니다. 거래·상환·정산의 실제 DB 변경은 앞서 Processor에서 호출한 서비스가 담당합니다. 입력 항목과 로그 집계 단위는 계좌입니다.

일반적인 설계에서는 Reader에 입력 조회, Processor에 변환·검증·업무 계산, Writer에 결과 저장·외부 전달을 배치합니다. 현재 구현은 Processor가 기존 동기화·매칭 서비스를 재사용하는 구조입니다. 발표에서는 일반 역할을 먼저 정의하고, 이어서 현재 코드가 어느 책임을 어디에 두었는지 설명하십시오.

### 현재 각 Job이 Step 하나인 이유와 여러 Step의 설계 기준

현재 10개 JobConfig는 각각 `.start(해당 Step)`을 연결하고 `.build()`로 Job을 완성합니다. Chunk Job은 계좌별 동기화·매칭 또는 계좌별 재시도 반복을 하나의 Step에 두었습니다. Tasklet Job은 대상 조회부터 집계까지 이어지는 서비스 절차를 하나의 Step에서 실행합니다. 현재의 한 Step은 그 절차의 최종 상태와 집계 경계입니다. 서비스 안에 여러 메서드가 있어도 Step 개수는 JobConfig에서 연결한 Step 객체 수로 결정됩니다.

여러 Step은 단계마다 서로 다른 입력·출력과 상태를 관리해야 할 때 사용할 수 있습니다. 설계 예시로 파일 검증 Step → DB 적재 Step → 보고 Step을 하나의 Job에 연결할 수 있습니다. 각 StepExecution이 구분되므로 어느 단계에서 실패했는지 확인하기 쉽고, 조건 전이와 재시작 정책을 단계별로 설계할 수 있습니다. 이 예시는 개념 설명용이며 현재 프로젝트 구현은 단일 Step 구조입니다. 재시작 동작은 이전 실행 상태, 동일 JobInstance 여부, Step 설정 등 실제 구성에 따라 결정됩니다.

Step을 잘게 나누는 기준은 메서드 수보다 운영 요구입니다. 독립적인 실행 결과, 트랜잭션 경계, 단계별 재시작, 다음 단계의 실행 조건을 구분할 필요가 있는지를 확인합니다. 현재 서비스의 REQUIRES_NEW는 서비스 트랜잭션 경계를 만들고, Step 분리는 프레임워크의 실행 단계와 이력 경계를 만듭니다. 두 경계를 구분해 설명하십시오.

### Chunk

Reader가 항목을 읽고 Processor가 항목을 처리하고 Writer가 묶음을 받습니다. 이 프로젝트의 Reader는 **연결 계좌 하나를 나타내는 DTO**를 항목으로 반환합니다. `.chunk(50)`은 그 항목 50개를 기준으로 처리 경계를 잡는 설정입니다. 이 값은 Writer가 받을 계좌 묶음의 크기와 Step 처리 경계를 설정합니다. 마지막 묶음은 50개보다 작을 수 있습니다.

일반적인 Chunk는 프레임워크가 chunk 단위 트랜잭션을 관리합니다. 다만 내부 서비스가 `REQUIRES_NEW`로 독립 커밋하거나 외부 API를 호출하면 독립 커밋된 DB 변경은 바깥 chunk 롤백 후에도 남고 외부 API의 처리 결과는 수신 시스템에 남습니다. 현재 Processor가 동기화와 매칭 서비스를 호출해 실질적 업무를 수행하고 Writer는 로그만 남기므로, “Writer에서만 DB에 쓴다”는 교과서식 설명은 이 코드에 맞지 않습니다.

Reader의 `pageSize(50)`은 DB 조회 페이지 크기입니다. `chunk(50)`과 우연히 같아도 서로 다른 설정입니다. 상각 서비스의 `CHUNK_SIZE=500`은 목록을 나누는 일반 코드 상수로, ID 목록을 나누어 쿼리에 전달할 크기를 정합니다.

### Tasklet

Tasklet은 업무 절차를 실행하는 인터페이스입니다. 현재 설정들은 기준일을 해석하고 서비스 메서드를 호출한 뒤 결과를 집계하고 `RepeatStatus.FINISHED`를 반환합니다. 서비스 내부에는 페이지 반복, 대상별 반복, 벌크 UPDATE가 존재할 수 있습니다. 현재 정산 Tasklet은 서비스에서 정산을 페이지 조회하고 참여자·결제의무를 반복 처리합니다.

Tasklet도 일반적으로 `CONTINUABLE`을 반환해 반복할 수 있습니다. 현재 8개가 한 호출에서 FINISHED를 반환하는 설계인 것입니다. 페이징을 서비스에서 직접 구현하는 것과 Spring Batch Reader의 실행 상태를 이용하는 것은 다르므로, 페이지를 사용한다는 이유만으로 자동 체크포인트 복구를 보장하지 않습니다.

### 왜 2개는 Chunk이고 8개는 Tasklet인가

계좌별 동기화와 재매칭은 입력이 명확히 “계좌 하나”이고 같은 작업을 반복합니다. 두 Job은 공통 Reader Factory를 공유하며 retry/skip 정책을 Step에 선언합니다. 이 반복을 Batch가 관리하는 것이 자연스럽습니다.

나머지는 단순 변환보다 절차 조합의 성격이 강합니다. 상환 연체는 벌크 UPDATE, 정기 생성은 회차 계산과 누락 회차 따라잡기, 정산 알림은 정산→참여자→의무 탐색, 방치 감지는 감지 기록과 미전송 전달, 정산 상각은 상각과 자동종결입니다. 기존 서비스에 이 절차와 트랜잭션 경계가 구현되어 있어 Tasklet이 얇은 실행 진입점 역할을 합니다. 향후 대상별 체크포인트와 프레임워크 재시작 제어가 필요해지면 Chunk 전환을 검토할 수 있습니다.

## 5. 전체 아키텍처를 설명할 때 짚을 네 계층

1. **시간과 중복 진입:** Spring `@Scheduled`가 한국시간 자정에 실행을 예약합니다. ShedLock은 JDBC와 DB 시간을 이용해 같은 이름의 스케줄 락을 획득합니다. 파이프라인은 최소 5분·최대 2시간, 개별 3개는 최소 5분·최대 30분 설정입니다. 최소 시간은 빠르게 끝났어도 락을 유지하는 시간이며 최대 시간은 장애 때 영구 잠금을 피하기 위한 만료 한도입니다. 실행이 한도를 넘으면 다른 노드 진입 가능성이 생깁니다.
2. **실행 조립:** JobOperator가 Job을 시작하고 JobConfig가 연결한 Step을 실행합니다. 7개 파이프라인과 3개 개별 스케줄은 모두 자정에 예약되지만 전체 10개의 완전한 선후 관계를 구성하지 않습니다. 실제 실행 중첩 여부는 Scheduler 스레드와 Job 실행기의 설정으로 결정됩니다.
3. **업무 처리:** 배치 서비스가 대상과 반복 절차를 관리하고 도메인 서비스가 실제 상태 변경·잠금·업무 규칙을 담당합니다. 은행 연동의 현재 클래스는 `MockBankClient`입니다. 실제 금융기관 운영 API와 연결됐다고 단정하지 않습니다.
4. **실행 관측:** JobRepository가 이력을 저장하고 Listener가 결과를 기록·전달합니다. Notification은 사용자용 저장 데이터이고 Slack은 배치 결과와 운영 경고 경로입니다.

파이프라인은 동기화 → 재매칭 → 정기 생성 → 상환 연체 → 정산 연체 → 정산 상각 → 상환 상각 순서로 start를 호출합니다. 앞 Job의 COMPLETED를 검사해 다음 Job을 허용하는 코드는 없고 예외도 잡고 계속합니다. 동기화 후 연체 판정을 두어 거래 반영 기회를 먼저 주는 구조로 읽을 수 있지만, 전체 의존성이 완전히 보장된다고 말해서는 안 됩니다.

## 6. 흐름 1 — 거래 동기화와 매칭 재시도

### 발표 스크립트

“두 Job은 같은 연결 계좌 목록을 사용합니다. 첫 번째는 은행 모사 API에서 마지막 동기화 ID 이후 거래를 가져와 저장하고 매칭합니다. 두 번째는 기존에 해결하지 못한 거래의 후보와 상태를 정리한 뒤 다시 매칭합니다. 새 거래 수집과 기존 거래 보정이라는 역할 차이가 있습니다.”

### 코드에서 볼 부분

- `domain/batch/transaction/reader/LinkedAccountSyncTargetReaderFactory.java`: AVAILABLE 연결 계좌를 ID 순으로 page 50개씩 읽는 공통 Reader.
- `domain/batch/transaction/sync/TransactionSyncJobConfig.java`: `.chunk(50)`, Reader/Processor/Writer, 특정 예외 retry/skip 연결.
- `domain/transaction/service/TransactionSyncMatchService.java`: `syncTransactions()` 다음 `bankMatchingService.execute()` 순서.
- `domain/transaction/service/TransactionSyncService.java`: 소유자 확인, 사용자 키, 마지막 동기화 ID, 외부 조회, 저장 및 커서 전진 위임.
- `domain/matching/service/BankTransactionRetryService.java`: 재시도 후보, 기존 후보 삭제, PENDING 초기화, 매칭 재실행, 소진 경고.

동기화에서 일시적 오류로 분류하는 예는 HTTP 502/503/504와 특정 연결·타임아웃 원인입니다. 코드의 `retryLimit(2)`를 보여주되 “최초 1회에 추가 2회로 반드시 총 3회”처럼 이 자료에서 검증하지 않은 정확한 총 호출 횟수로 바꾸어 설명하지 마십시오. 해당 예외는 skipLimit 50이며, Step은 RetryableBankTransactionFetchException을 retry·skip 대상으로 지정합니다.

재매칭 Job은 Step에서 `skip(Exception.class)`와 skipLimit 50을 사용합니다. 업무 거래의 누적 재시도 정책은 FAILED 5, UNMATCHED/NEEDS_CHECK 14입니다. 이 정책과 은행 조회 오류의 즉시 재시도 설정을 섞지 마십시오. 소진된 미해결 거래는 Slack 확인 대상입니다. 두 거래 Job은 계좌의 동기화 커서와 거래의 상태·재시도 횟수로 처리 대상을 정합니다.

## 7. 흐름 2 — 정기정산 회차 생성

### 발표 스크립트

“반복 설정은 시작일·주기·종료일 등 생성 규칙을 보관합니다. 오늘 생성해야 할 회차를 계산하고 정산, 참여자, 결제의무, 연결 계좌를 실제로 만듭니다. 누락된 회차도 따라잡되 하나의 설정당 한 번에 최대 31회차까지 처리합니다.”

최근 회차와 누적 회차 수를 이용해 시작일 기준 N번째 날짜를 계산합니다. DAILY/WEEKLY/MONTHLY/YEARLY를 지원하고 월·년 단위의 없는 날짜는 말일로 보정합니다. 단순히 직전 날짜에 한 달을 계속 더하는 방식이라고 설명하지 마십시오.

`RecurringSettlementCycleService.generateOneCycle()`은 최근 회차를 잠근 뒤 호출자가 알고 있던 직전 회차와 같은지 검사합니다. 다른 실행이 이미 회차를 만들었으면 동시 생성으로 건너뜁니다. ACTIVE 참여자를 복사하며 균등 분배는 인원수에 맞춰 계산하고 개별 분배는 이전 금액을 승계합니다. 연결 계좌도 승계합니다. 계좌 저장에 실패하면 경고를 시도하고 예외를 다시 던지므로, 계좌 없이 회차가 반드시 성공 커밋된다고 말하지 마십시오.

한 회차가 REQUIRES_NEW 트랜잭션입니다. 앞 회차들이 커밋된 후 다음 회차가 실패하면 앞 회차는 유지됩니다. 이전 회차가 없거나 ACTIVE 참여자가 없으면 정상 스킵으로 처리하고, 31회차 상한 도달도 실패로 집계하지 않습니다. `succeeded`는 성공 처리한 정기설정 수라서 실제 생성 회차 수와 같지 않습니다.

## 8. 흐름 3 — 두 종류의 연체 처리

### 발표 스크립트

“상환은 스케줄의 상태를 OVERDUE로 바꿉니다. 정산은 정산 자체의 상태를 바꾸지 않고 참여자 결제의무에 overdueSince를 기록합니다. 같은 연체라는 말이지만 저장 방식이 다릅니다.”

상환 연체의 조건은 `status=PENDING AND dueDate<baseDate`입니다. Repository의 벌크 UPDATE로 바꿉니다. dueDate가 오늘인 스케줄은 PENDING을 유지하고 다음 날부터 연체 조회 조건을 충족합니다.

정산 연체는 IN_PROGRESS 정산을 100개씩 읽습니다. `OverdueCriteria`가 일반 정산(SHARED)은 dueDate, 정기 정산(RECURRING)은 cycleDate를 기준으로 선택합니다. 기준일이 baseDate 이전이면 ACTIVE 참여자를 찾고 ACTIVE 의무 중 UNPAID/PARTIALLY_PAID이며 연체 시작일이 없는 대상을 갱신합니다. 연체 시작일은 정산 기준일의 다음 날 00:00입니다. 정산 단위 REQUIRES_NEW로 격리하고 실패를 집계한 후 다른 정산을 처리합니다.

## 9. 흐름 4 — 두 종류의 리마인드

### 발표 스크립트

“D-3, D-1, 당일에 아직 해결하지 않은 사람에게 알림을 저장합니다. 상환은 채무자와 스케줄을 연결하고, 정산은 참여자의 최신 활성 미납 결제의무를 연결합니다.”

상환은 PENDING 스케줄의 dueDate가 baseDate+3, +1, 당일인 대상을 찾습니다. 계약에서 채무자 ID를 찾지 못하면 skipped로 집계합니다. `ReminderNotificationSender.sendOne()`이 독립 트랜잭션에서 `createIfAbsent()`를 호출합니다. 실패는 개별 집계하며 10건 초과, 즉 11번째 실패에서 예외를 던집니다. 이전에 독립 커밋한 알림은 남습니다.

정산은 IN_PROGRESS 정산을 훑고 기준일로 단계를 판정합니다. ACTIVE 참여자의 최신 활성 결제의무 중 미해결인 의무만 대상으로 삼습니다. 의무 ID를 referenceId로, 정산 ID를 secondaryReferenceId로 넘겨 중복 확인과 화면 참조에 활용합니다. 개별 알림은 `createIfAbsentInNewTransaction()`으로 저장합니다. 정산 처리 실패와 알림 저장 실패를 집계하고 계속합니다.

중복 확인으로 이미 있던 알림을 새로 만들지 않아도 서비스 호출 자체는 정상 처리로 집계될 수 있습니다. 따라서 처리 건수와 신규 알림 저장 건수를 같은 것으로 말하지 마십시오. 또한 이 경로는 NotificationService를 통해 알림 레코드를 DB에 저장합니다.

## 10. 흐름 5 — 정산 장기방치 감지

### 발표 스크립트

“기준일에서 3일 이상 지났는데 여전히 IN_PROGRESS인 정산을 운영 확인 대상으로 기록합니다. 기록과 Slack 전달을 분리해 전송 실패를 다음 실행에 다시 시도합니다.”

조건은 `referenceDate+3일 <= baseDate`입니다. 조회한 정산의 상태와 dueDate/cycleDate로 판정한 뒤 방치 기록을 저장하고 Slack을 전송합니다. `(settlementId, referenceDate)`를 기준으로 감지 기록 중복을 방어합니다. 이후 새 기록뿐 아니라 기존 PENDING도 조회해 전달합니다. 감지에서 예외가 나도 이전 미전송 전달을 시도하도록 두 절차가 구성되어 있습니다.

Notifier는 전달할 기록을 잠그고 REQUIRES_NEW 안에서 Slack을 시도합니다. 성공하면 SENT로 바꾸며 실패하면 PENDING으로 남습니다. 외부 전송 성공과 DB 커밋을 하나의 원자적 트랜잭션으로 묶지는 못하므로, 전송 직후 커밋 전 장애가 나면 중복 메시지가 가능합니다. 이것이 at-least-once 전달입니다. “DB에 기록하니까 정확히 한 번”이라고 설명하지 마십시오.

## 11. 흐름 6 — 정산 상각과 상환 상각

### 발표 스크립트

“장기 연체 의무와 스케줄을 WRITTEN_OFF 상태로 정리합니다. 정산은 상각 후 모든 의무가 해결됐는지 확인해 정산을 자동종결할 수 있습니다. 상환은 스케줄 상태만 바꾸는 경로입니다.”

정산 후보는 ACTIVE이고 UNPAID/PARTIALLY_PAID이며 overdueSince가 `baseDate-30일 00:00` 이하인 의무입니다. 잠금 조회에서 현재 활성·미납 상태를 다시 확인합니다. 후보 조회와 실제 변경 사이 납부 등으로 상태가 달라졌을 수 있으므로 후보 수와 실제 상각 수는 다를 수 있습니다. 변경 후 관련 정산마다 `autoCloseIfAllResolved()`를 호출합니다. 이 검사는 납부액 충족 또는 WRITTEN_OFF/EXCLUDED/CANCELLED 등 해결된 상태인지 확인합니다. 무조건 모든 관련 정산을 닫지는 않습니다.

상환 후보는 OVERDUE이고 dueDate가 baseDate-30일 이하인 스케줄입니다. UPDATE에서도 기존 상태가 OVERDUE인지 조건을 걸어 현재 상태를 방어합니다. 이 Job에는 대출 계약 자동종결 호출이 없습니다.

상각 기준 날짜 예시: dueDate와 정산 기준일이 10/1이면 상환은 10/31에 대상이 될 수 있습니다. 정산의 overdueSince는 10/2 00:00이므로 11/1에 30일 조건에 도달합니다. 이름이 둘 다 “연체 30일 상각”이라고 같게 말하면 이 차이를 놓칩니다.

두 경로의 500개 분할은 DB 쿼리 대상 목록 크기를 나누는 것입니다. 반복 전체를 REQUIRES_NEW 메서드가 감싸므로 500개씩 나눈 UPDATE들이 같은 트랜잭션에서 실행되고 메서드 종료 시 함께 커밋됩니다. 정산은 상각 변경을 별도 트랜잭션으로 커밋한 뒤 정산별 자동종결을 독립 트랜잭션으로 시도합니다. 자동종결이 실패해도 앞서 독립 커밋한 상각 결과는 유지됩니다. 그렇다고 실패한 자동종결이 이 Job에서 항상 다음 날 자동 재시도된다고 단정해서도 안 됩니다. 다음 실행의 상각 후보에 다시 포함되는지 등 후속 경로를 따로 확인해야 합니다.

## 12. 기술과 공통 인프라의 역할

| 기술·구성 | 이 코드에서의 역할 | 꼭 말해야 할 이유 |
|---|---|---|
| Java 17 | 프로젝트 언어/toolchain 설정 | 실제 build.gradle 기준 |
| Spring Boot 4.1.0 | 애플리케이션과 의존성 구성 | Batch 세부 버전은 starter 의존성 관리에 따름. 별도 확정 버전을 추측하지 않음 |
| Spring Scheduling | @Scheduled 실행 예약 | Batch와 다른 책임 |
| Spring Batch | Job/Step, Chunk/Tasklet, 실행 이력, retry/skip | 반복 작업을 운영 가능한 실행 단위로 관리 |
| Spring Data JPA / JPQL | 대상 조회·페이지·벌크 UPDATE·잠금 | 반복 처리의 데이터 접근 |
| Spring Transaction | PlatformTransactionManager, @Transactional, REQUIRES_NEW | 실패 영향 범위와 커밋 단위 제어 |
| JDBC JobRepository | 실행 메타데이터 영속화 | 프로세스 종료 후에도 이력 확인 |
| ShedLock 7.10.1 / JDBC provider | DB 기반 스케줄 락 | 다중 Pod 스케줄 진입 중복 억제 |
| MariaDB 드라이버 | 관계형 DB 연결 의존성 | 업무·메타데이터 저장 기반. 운영 접속 구성 실측은 별도 |
| SlackNotifier | 결과·방치·재시도 소진 등 운영 메시지 | 실패 원인을 운영자가 확인 |
| MockBankClient | 외부 은행 모사 거래 조회 | 네트워크 장애를 별도로 처리해야 함 |

Redis, S3, JWT 등 다른 프로젝트 의존성도 있지만 여기서 다룬 배치 흐름의 핵심 직접 경로로 모두 나열할 필요는 없습니다.

JobConfig는 단순 파일 명칭입니다. 각 JobConfig는 `@Configuration` 클래스이며 `@Bean`으로 Job과 Step을 조립합니다. `.start(step)`이 업무 단계 연결이고 `.listener(...)`는 관측 코드를 연결합니다. `.tasklet(..., transactionManager)` 또는 `.chunk(50)`이 실행 방식과 트랜잭션 관리를 결정합니다. 업무 규칙은 서비스를 호출해 처리하므로 설정과 로직을 따로 읽을 수 있습니다.

Listener는 실행 생명주기에 붙는 콜백입니다. 현재 LoggingJobExecutionListener의 beforeJob은 시작 로그, afterJob은 성공·실패 판단과 소요시간·Step별 read/write/skip 집계를 Slack으로 전달합니다. Listener는 업무 성공 여부를 대신 판정하거나 개별 실패를 모두 자동 복구하지 않습니다. BaseSkipListener는 스킵된 지점과 원인을 로그에 남깁니다.

공통 인프라를 두지 않으면 10개 Job에 실행 이력, 로그 형식, 파라미터 생성, 운영 알림을 반복 구현해야 합니다. 형식이 달라지면 비교와 장애 분석도 어려워집니다. 공통화는 이를 일관되게 유지하기 위한 것입니다. 다만 모든 오류 정책까지 강제로 같게 만들지는 않았습니다. 상환 리마인드 임계값과 정산 리마인드 정책처럼 업무별 차이가 남아 있습니다.

현재 AbstractDailyBatchScheduler와 DailyJobParameters는 개별 스케줄 3개가 공유합니다. 7개 파이프라인은 파라미터를 자체 코드로 생성합니다. JobDisplayNames는 공통 표시명이지만 DevBatchTriggerController에는 별도 표시명 Map도 있습니다. 거래 JobConfig의 실제 설정은 chunk 50과 skipLimit 50입니다. “완벽하게 하나로 공통화했다”보다 “반복되는 운영 기능을 공통화했다”고 설명하십시오.

## 13. 운영 결과를 해석하는 방법

1. Job 상태만 보지 말고 failed·skip 집계를 같이 봅니다. 특히 Tasklet은 예외를 내부에서 잡고 FINISHED를 반환하면 일부 실패에도 COMPLETED가 될 수 있습니다.
2. 집계 단위를 확인합니다. 거래는 계좌, 정기 생성은 정기설정, 정산 연체는 처리한 정산, 상각은 의무/스케줄, 방치 감지는 신규 감지 기록입니다. Tasklet readCount가 0이라도 서비스를 통해 DB를 조회했을 수 있습니다.
3. 상환 리마인드는 skipped/failed를 ExecutionContext에 별도로 기록합니다. 공통 Listener가 해당 키들을 모두 읽어 Slack에 출력하는 구조는 아니므로 상세 집계가 공통 메시지에 전부 노출된다고 말하지 않습니다.
4. ShedLock은 스케줄 호출 경로의 락입니다. dev 컨트롤러 수동 실행은 같은 Scheduler 메서드를 경유하지 않습니다. 업무 데이터 잠금과 멱등 조건을 별도로 둔 이유입니다.
5. cron 시간대는 Asia/Seoul이지만 LocalDate.now()의 기준은 JVM 기본 시간대입니다. 배포 환경에서 둘이 일치하는지 확인해야 합니다.
6. 다운타임 후 모든 Job의 누락 업무를 자동 따라잡는다고 말하지 않습니다. 회차 생성에는 명시적 catch-up이 있고 다른 Job은 각자의 조회 조건에 따라 동작합니다. 정확히 D-3/D-1/당일만 고르는 알림은 실행일 기준 세 날짜에 해당하는 대상만 조회합니다.

## 14. 예상 질문과 답변

**Q. 스케줄 메서드에서 서비스를 바로 부르면 안 되나요?**

가능합니다. 다만 실행 이력·상태·건수·오류 정책을 직접 관리해야 합니다. 이 프로젝트는 10개 작업을 공통 실행 구조와 관측 체계로 운영하기 위해 Batch를 사용합니다.

**Q. 모든 Job을 Chunk로 바꾸면 더 좋나요?**

벌크 UPDATE와 복합 서비스 절차에는 Tasklet을 사용하고, 동일 항목별 반복과 표준 skip 정책에는 Chunk를 적용할 수 있습니다. 반대로 많은 독립 대상에 체크포인트·표준 skip 정책이 중요하면 Chunk가 유리할 수 있습니다. 실제 데이터량과 장애 복구 요구를 보고 판단합니다.

**Q. 7개를 하나의 Job, 여러 Step으로 만들 수 있나요?**

가능하지만 실패 시 다음 단계를 어떻게 할지와 개별 실행·재시작 단위가 달라집니다. 현재는 별도 Job을 호출해 독립 이력을 남기고 한 실행 요청의 예외에도 다음 호출을 계속합니다. 현재 설계와 다단계 단일 Job을 동일하게 설명하면 안 됩니다.

**Q. REQUIRES_NEW면 어떤 점이 중요한가요?**

바깥 트랜잭션을 잠시 중단하고 새 트랜잭션을 시작합니다. 내부 성공 커밋은 바깥 실패와 별개로 남을 수 있습니다. 회차별 생성·알림별 저장·정산별 갱신을 격리하는 장점이 있지만, 독립 트랜잭션으로 먼저 커밋한 회차·알림·상각 결과는 후속 단계가 실패해도 유지됩니다. 보통 다른 Spring Bean을 통해 호출해야 프록시 기반 트랜잭션이 적용됩니다.

**Q. ShedLock과 데이터 잠금은 왜 둘 다 있나요?**

ShedLock은 스케줄 시작 중복을 줄이고, 데이터 잠금은 개별 의무·회차·정산을 동시에 변경할 때 일관성을 보호합니다. 서로 보호하는 범위가 다릅니다. 수동 실행과 외부 요청 경로도 있어 데이터 단위 방어가 필요합니다.

**Q. 상각이면 돈을 낸 것으로 처리하나요?**

이 코드는 상태를 WRITTEN_OFF로 변경합니다. 납부 금액 반영은 별도 결제 처리 경로에서 수행합니다. 이 자료는 코드상 상태 전환을 설명하며 법적 채무 소멸이나 회계상 처리의 의미까지 단정하지 않습니다.

**Q. 운영 장애가 나면 무엇부터 확인하나요?**

Job 이름·baseDate·runId·실행 상태와 Step 집계를 먼저 확인합니다. 그 뒤 스킵 로그, 서비스별 실패 집계, 외부 은행 응답, Slack 미전송 기록을 문제 유형에 맞게 봅니다. 재실행 전에 runId로 새 실행을 만드는지, 기존 커밋이 남는지, 해당 업무의 중복 방어가 무엇인지 확인합니다.

## 15. 소스 탐색 지도와 자료 재생성

아래 경로는 저장소 루트 기준입니다. HTML 코드 카드에는 실제 파일과 줄 번호를 함께 표시했습니다.

| 살펴볼 내용 | 경로 |
|---|---|
| 실행 순서 | src/main/java/org/teamsai/saibackend/domain/batch/orchestration/DailyBatchPipelineScheduler.java |
| Job/Step 설정 | src/main/java/org/teamsai/saibackend/domain/batch/**/**JobConfig.java |
| 공통 실행/관측 | src/main/java/org/teamsai/saibackend/domain/batch/common/ |
| 다중 인스턴스 스케줄 락 | src/main/java/org/teamsai/saibackend/global/config/SchedulerLockConfig.java |
| 개발 수동 실행 | src/main/java/org/teamsai/saibackend/domain/batch/support/controller/DevBatchTriggerController.java |
| 회차 생성·연체 갱신·자동종결 | src/main/java/org/teamsai/saibackend/domain/settlement/service/ |
| 정산 기준일·알림 대상 | src/main/java/org/teamsai/saibackend/domain/settlement/support/ |
| 의무 상각·연체 | src/main/java/org/teamsai/saibackend/domain/payment/service/SettlementPaymentService.java |
| 상환 상태 일괄 변경 | src/main/java/org/teamsai/saibackend/domain/contract/service/RepaymentScheduleService.java |

`SettlementBatchConfig`의 settlementStep은 바로 FINISHED를 반환하는 빈 작업이고 이 Step을 시작 단계로 연결하는 추가 Job 정의는 현재 소스에서 확인되지 않습니다.

발표용 HTML 생성 스크립트는 `node docs/batch-training/build-materials.cjs`, 가이드 HTML 생성 스크립트는 `node docs/batch-training/build-guide.cjs`입니다. 코드 카드는 실행 시 로컬 Java 파일에서 추출하므로 소스 변경 후 범위를 다시 확인해야 합니다. 설명 문구는 자동 갱신되지 않습니다. 작성 과정에서 업무 코드나 기존 작업 파일은 수정하지 않았습니다.
