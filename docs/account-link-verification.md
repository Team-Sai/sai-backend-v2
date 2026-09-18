# Account 연동의 트랜잭션·멱등성

## 배포

`src/main/resources/db/account_link_operation.sql`을 애플리케이션 시작 전에 적용하거나,
사용하는 프로필의 `spring.sql.init.schema-locations`에 추가한다.
현재 작업 공간의 dev/prod 프로필에는 추가했다. 이 프로필 파일들은 Git 추적 대상이 아니므로
다른 환경의 설정에도 별도로 반영해야 한다. 기존 계좌 테이블에는
`UNIQUE (user_id, account_id)`가 있어야 한다.

모든 키 변경 인스턴스를 함께 배포한다. 구버전 인스턴스는 새 잠금 규약을 따르지 않는다.

## 처리 방식

- Controller는 입력 검증과 리다이렉트를 담당한다.
- `AccountLinkCoordinator`는 계좌 연동, 키 발급, 선택 계좌 등록을 사용자 단위로 보호한다.
- `UserLinkLock`은 MariaDB의 동일 서버·스키마를 공유하는 인스턴스 간 named lock을 사용한다.
  잠금 전용 연결을 커밋·보상이 끝날 때까지 유지하고 finally에서 해제한다.
  경합 시 기다리지 않고 충돌을 반환한다. DB 작업용 연결이 추가로 필요하므로 풀 크기는 최소 2여야 한다.
  인스턴스별 동시 잠금 수는 Hikari 풀 설정의 절반으로 제한해 잠금만으로 연결 풀이 소진되는 상황을 막는다.
  여러 독립 primary DB에 쓰기를 분산하는 구성에는 이 잠금을 그대로 사용할 수 없다.
- 외부 은행 호출은 DB 트랜잭션 밖에서 실행한다.
- `AccountLinkService`는 처음 읽은 키를 조건으로 갱신하고 계좌·완료 기록을 함께 커밋한다.
- `LinkedAccountWriter`는 여러 계좌 저장을 한 트랜잭션으로 실행한다.
  예상된 사용자/계좌 중복은 저장소 내부에서 처리하며, 그 밖의 실패는 전체를 롤백한다.
- state JWT에는 고유 jti를 넣고, state의 SHA-256을 처리 ID로 사용한다.
  같은 state와 동일한 키·계좌 집합의 완료 요청은 재실행하지 않는다.
  같은 state의 다른 내용은 거절한다. 성공 기록은 이후 키가 바뀌어도 과거 요청의 결과만 반환한다.
- 선택 등록 API는 이전과 같이 이번에 새로 저장된 계좌만 반환한다.
- 거래 동기화는 커서보다 새로운 응답일 때만 커서와 잔액을 함께 갱신한다.
  잔액이 null이면 기존 잔액을 유지한다.

## 실패 기록과 복구

`account_link_operation`에는 실제 키가 포함된다. 테이블 접근 권한은 users 테이블과 동일하게 제한한다.
로그에는 사용자 ID와 처리 ID만 출력한다.

| 상태 | 의미와 처리 |
| --- | --- |
| COMPLETED | 로컬 키·계좌·완료 기록이 함께 커밋됨. 동일 콜백은 은행 호출 없이 성공 |
| FAILED | 로컬 저장 실패 후 보상 완료. 새 연동 시작 가능 |
| COMPENSATION_PENDING | 보상 미완료. 유효한 동일 콜백 재전송 시 로컬 키를 다시 확인하고 보상만 재시도 |
| CONFIRM_UNKNOWN | confirm 예외로 은행 성공 여부를 확정할 수 없음. 자동 confirm·보상을 하지 않음 |
| PROCESSING | 작업 중이거나 프로세스가 중단됨. 완료 여부 확인 전 새 키 변경을 차단 |

미해결 상태가 있으면 다른 연동·키 발급·선택 등록을 차단한다.
보상은 현재 DB 키가 원래 키와 같을 때만 수행하며, 새 키와 이전 키가 같으면 은행 복원을 생략한다.
보상 재시도 시에는 은행 revoke/restore가 이미 처리된 요청에 대해 안전한 응답을 주어야 한다.
이미 처리된 요청을 은행이 오류로 반환하면 기록은 미해결로 유지된다.

현재 은행 클라이언트에는 처리 결과 조회나 요청 ID 기반 confirm이 없다.
따라서 `CONFIRM_UNKNOWN`, 중단된 `PROCESSING`, 만료된 콜백의 보상은 운영자가 은행 상태와
로컬 키·계좌를 확인해 조정해야 한다. 타임아웃이나 경과 시간만으로 FAILED 처리하거나
처리 기록을 삭제하면 안 된다. 운영 복구 시 관련 사용자의 요청을 중지하고 상태를 확인한다.
자동 복구를 완성하려면 은행의 멱등 confirm 및 결과 조회 API가 추가로 필요하다.

## 실제 MariaDB 테스트

기존 개발 DB 대신 전용 테스트 DB를 사용한다. 아래 포트와 컨테이너 이름은 사용 중이지 않아야 한다.

```powershell
docker run -d --name sai-account-verification -e MARIADB_ROOT_PASSWORD=account-test-only -e MARIADB_DATABASE=account_test -p 127.0.0.1:13316:3306 mariadb:11.4
$env:SAI_ACCOUNT_DB_TEST='true'
.\gradlew.bat test --tests 'org.teamsai.saibackend.domain.account.*' --tests 'org.teamsai.saibackend.domain.link.*' --tests 'org.teamsai.saibackend.domain.transaction.*'
docker stop sai-account-verification
docker rm sai-account-verification
```

DB 시작 완료 후 테스트한다. 접속 값은 `SAI_ACCOUNT_TEST_DB_URL`, `SAI_ACCOUNT_TEST_DB_USER`,
`SAI_ACCOUNT_TEST_DB_PASSWORD`로 변경할 수 있다. 전용 스키마만 지정한다.
테스트는 필요한 테이블을 생성하며, 각 테스트가 만든 사용자의 데이터만 정리한다.
`SAI_ACCOUNT_DB_TEST=true`가 없으면 DB 통합 테스트는 명시적으로 건너뛴다.
은행 API는 mock이므로 실제 은행 서버와의 HTTP 계약 검증을 대신하지 않는다.

## 검증 결과 (2026-09-18)

계좌·연동·거래 도메인, 은행 클라이언트, 계약 계좌·정산 계좌 테스트를 실행했다.
총 151개가 통과했고 실패·오류·건너뜀은 0개다.
`BankTransactionPersistenceServiceTest` 8개와 실제 MariaDB 통합 테스트 21개를 포함한다.
동시 콜백·키 발급·선택 등록 경합, 중복 INSERT 후 커밋, 다중 저장 롤백,
완료 요청 재전송, 보상 실패·재시도, confirm 미확정 상태 차단,
커서·잔액 역행 방지, 잠금 연결의 풀 소진 방지를 확인했다.

테스트 보고서: `build/reports/tests/test/index.html`.
검증에 사용한 전용 MariaDB 컨테이너는 종료·삭제했고, 기존 개발 DB는 사용하지 않았다.
