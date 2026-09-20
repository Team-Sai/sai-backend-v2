# 계좌 연동 recover-key 통합

sai-backend-v2와 sai-mock-bank에 적용한 복구 동작입니다.

## API

`POST /api/link/recover-key`는 `X-Internal-Api-Key`로 보호되는 서버 간 API입니다.

```json
{"userToken":"사용자 토큰", "currentUserKey":"실패한 연동의 새 키", "previousUserKey":null}
```

최초 연동이면 previousUserKey는 null, 재연동이면 기존 키를 전달합니다.

- 성공 및 이미 복구된 요청: 204
- 잘못된 요청: 400
- 내부 API 키 누락/오류: 401
- 은행 사용자 없음: 404
- 예상하지 못한 활성 키 또는 다른 pending 키: 409

## 처리 흐름

백엔드는 로컬 저장 실패 보상과 미해결 작업 재시도 모두 `AccountLinkCoordinator.recover()`를 사용합니다. 은행 복구가 성공한 뒤에만 작업을 FAILED로 마감합니다. 복구가 실패하면 미해결 상태를 유지합니다. 이미 COMPLETED인 콜백은 복구하지 않습니다.

새 연동 시작(`POST /api/accounts/link/start`)은 state 발급 전에 복구합니다. 직접 키 발급 및 계좌 선택 요청도 미해결 작업을 먼저 복구합니다. 따라서 기존 state가 만료되어도 로그인한 사용자의 새 연동 시작으로 복구할 수 있습니다. 동일한 미완료 콜백을 재전송하면 안전하게 취소한 뒤 실패 리다이렉트하므로 연동을 다시 시작해야 합니다.

은행의 `BankLinkService.recoverUserKey()`는 사용자 행을 SELECT FOR UPDATE로 잠근 뒤 활성/pending 키를 검증하고 복원합니다.

| 현재 상태 | 처리 |
|---|---|
| 최초 연동, 새 키 pending | pending 취소, 활성 키 없음 |
| 재연동, 기존 키 active + 새 키 pending | 기존 키 유지, pending 취소 |
| 새 키 active | 기존 키로 복원, 최초 연동이면 활성 키 제거 |
| 이미 복구됨 | 성공 응답 |
| 다른 active/pending 키 | 409, 변경 없음 |

confirm이 먼저 끝나면 새 활성 키를 복원합니다. 복구가 먼저 끝나면 pending 키가 제거되어 늦게 도착한 confirm이 실패합니다. pending/issued-at/expires-at 필드는 함께 정리합니다. 이전 활성 키의 발급 시각은 저장되어 있지 않으므로 active `issued_at`은 변경하지 않습니다.

## 변경 위치

### sai-backend-v2

- `domain/link/service/AccountLinkCoordinator.java`: 보상·재시도 복구 통합
- `domain/link/service/LinkOperationStore.java`: 미해결 작업 조회
- `domain/link/controller/AccountLinkFlowController.java`: 새 state 발급 전 복구
- `global/client/MockBankClient.java`: recoverUserKey 호출, restoreUserKey 제거
- `domain/link/service/UserLinkLock.java`: 동시 실행 수 기본값 1

### sai-mock-bank

`src/main/java/org/teamsai/saimockbank/` 기준:

- `domain/link/controller/BankLinkCallbackController.java`: recover-key 요청 검증 및 204 응답
- `domain/user/service/BankLinkService.java`: 트랜잭션 내 상태 검증·복구
- `domain/user/mapper/UserMapper.java`: 잠금 조회/복구 메서드
- `domain/identity/exception/IdentityErrorCode.java`: KEY_RECOVERY_CONFLICT
- `global/config/SecurityConfig.java`: 내부 API 인증 체인에 recover-key 등록
- `src/main/resources/mapper/BankUserMapper.xml`: FOR UPDATE 조회 및 복구 UPDATE

기존 DomainException 처리기를 사용하므로 전역 예외 처리기 추가 변경은 필요하지 않습니다.

## API 정리 및 배포

`restore-key`, `restoreUserKey`, `restoreActiveKey`는 양쪽 구현에서 제거했습니다. `revoke-key`는 별도의 활성 키 폐기 API로 유지하며 연동 실패 보상에서는 호출하지 않습니다. 현재 백엔드의 UserKeyRevoker 외에는 직접 호출이 없고, 회원 탈퇴 서비스에도 연결되어 있지 않습니다.

스키마 변경은 없습니다. 구 백엔드는 restore-key를 호출하므로 두 서비스를 함께 전환해야 합니다. 순차 무중단 배포가 필요하면 기존 restore-key를 잠시 유지하는 호환 배포 단계를 별도로 두어야 합니다.

## 테스트

- 백엔드: 실패 시 로컬 롤백, 최초·재연동 복구, 응답 유실 재시도, 중복 콜백, 완료된 작업 보호, 신규 state 발급 순서, 탈퇴 회귀 테스트
- mock-bank: 입력/내부 API 인증/409 응답, 최초·재연동 및 반복 복구, 다른 키 보호
- 실제 MariaDB: MyBatis 매핑, 복구 SQL, pending 취소 이후 confirm 거절, confirm 완료 이후 복구, 동시 실행, 다른 트랜잭션 행 잠금 대기

실제 DB 테스트는 운영 DB가 아닌 별도 MariaDB에서 실행합니다.
백엔드는 `SAI_ACCOUNT_DB_TEST=true`, mock-bank는 `SAI_BANK_DB_TEST=true` 및 `SAI_BANK_TEST_DB_URL`, `SAI_BANK_TEST_DB_USER`, `SAI_BANK_TEST_DB_PASSWORD` 환경변수로 활성화합니다.

### 실행 결과 (2026-09-21)

- 백엔드 관련 테스트 91개 통과: 실제 MariaDB 통합 테스트 27개 포함.
- mock-bank 관련 테스트 54개 통과: 실제 MariaDB 통합 테스트 13개 포함.
- 실패/오류/건너뛴 테스트 없음. 전체 저장소 테스트가 아닌 변경 관련 테스트 실행 결과입니다.
- 백엔드 동시 실행 테스트의 고정 인원 5명을 실제 account-link.max-concurrent 설정값으로 수정했습니다.
- 전용 MariaDB 11.4 임시 컨테이너에서 실행했으며 완료 후 컨테이너를 정리했습니다.
