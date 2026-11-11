# 금액 일치 거래의 실패 상태 진단

2026-11-11 로컬 MariaDB 11.4의 전용 DB `127.0.0.1:13317/sai_matching_diagnostic`에서 검증.
운영 데이터와 애플리케이션 코드는 변경하지 않았다.

## 검증 범위

`MatchingFailureDiagnosticIntegrationTest`: Spring 트랜잭션 프록시, 실제 MariaDB,
납부 기록 저장, 은행 거래 상태 저장, 재시도 조회를 사용한다.
후보 발견 결과는 Mockito로 제어하며, 알림 발송 및 계좌 서비스 등 주변 의존성은 mock이다.
재시도는 배치 processor가 호출하는 `retryForAccount`를 chunk와 같은 외부 트랜잭션 안에서 호출했다.
JobLauncher와 실제 계좌 reader 전체를 실행한 테스트는 아니다.
스키마는 Hibernate create-drop으로 생성했으며 운영 마이그레이션/스키마 차이는 검증하지 않았다.

## 결과: 5건 모두 통과

분담금 25,000원, 기존 납부 24,000원, 새 입금 1,000원을 기준으로 비교했다.

| 조건 | 실제 저장 결과 |
| --- | --- |
| PENDING, 정확히 일치하는 후보 1개 | APPLIED, 납부 합계 25,000원 |
| 정확히 일치하는 후보 2개 | NEEDS_CHECK |
| 기존 FAILED, retry_count=4 | 5번째 재시도에서 APPLIED |
| 기존 FAILED, retry_count=5 | FAILED 유지, 후보 조회 자체가 실행되지 않음 |
| 같은 후보를 중복 저장하여 실제 unique 제약 위반 발생 | 전체 롤백, PENDING 유지 |

마지막 경우에는 MATCHING_CANDIDATE_SAVE_FAILED를 분류한 뒤 상태 변경 조회에서
Hibernate AssertionFailure("null identifier", 예외 후 flush)를 일으킨다.
따라서 일반 DB 오류를 주입하면 반드시 FAILED가 저장된다는 이전 설명은 정확하지 않다.

## 확인된 원인과 한계

실패 상태 유지의 재현 가능한 원인은 재시도 한도다.
BankTransactionQueryRepository는 FAILED 거래 중 retry_count < 5만 반환한다.
일반 BankMatchingService도 PENDING만 처리하므로, 한도에 도달한 실패 거래는
나중에 잔액이 입금액과 같아져도 자동으로 재검토되지 않는다.

이 테스트는 기존 FAILED 상태를 시드한 것으로, 정상 거래가 최초로 FAILED로 전환되는
운영 사건을 재현한 것은 아니다. 사용자 화면의 해당 거래가 retry_count=5 이상인지,
최초 실패가 무엇인지는 운영 DB 및 로그 확인 전까지 확정할 수 없다.

## 운영에서 읽기 전용으로 확인할 쿼리

```sql
SELECT bt.bank_transaction_id, bt.linked_account_id, bt.amount,
       bt.counterparty_name, bt.transaction_at, bt.processing_status, bt.retry_count,
       n.notification_id, n.created_at AS notification_created_at
FROM bank_transaction bt
JOIN notification n
  ON n.reference_id = bt.bank_transaction_id
 AND n.secondary_reference_id = bt.linked_account_id
 AND n.notification_type = 'BANK_TRANSACTION_MATCHING_REVIEW'
WHERE n.user_id = :user_id
  AND bt.amount = 1000
ORDER BY bt.transaction_at DESC;

SELECT * FROM bank_transaction_match_candidate
WHERE bank_transaction_id = :bank_transaction_id;

SELECT * FROM payment_record
WHERE bank_transaction_id = :bank_transaction_id;
```

해당 로그의 `Auto matching transaction failed`에 기록된 transactionId/errorCode/원인 예외도 대조한다.

## 再実行

위 전용 포트에 빈 진단 DB를 준비하고 PowerShell에서 실행한다.
전용 DB의 테이블은 테스트 시작·종료 시 create-drop된다.

```powershell
$env:SAI_MATCHING_DIAGNOSTIC='true'
./gradlew.bat integrationTest --tests '*MatchingFailureDiagnosticIntegrationTest'
```

환경 변수가 없는 일반 실행에서는 이 진단 테스트를 건너뛴다.
