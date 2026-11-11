# REQUIRES_NEW 정합성 부하테스트 인수인계

작성 기준: 2026-10-28 (KST)

## 포함 파일

- `scripts/account-sync-consistency.js`: 계좌별 sync 1회 실행. 재시도 없이 한 번 호출해 불일치 재현 여부를 확인합니다.
- `scripts/account-sync-load.js`: ramping VU 반복 계좌 동기화 성능 부하.
- `sql/00_rcb01_fixture_inventory.sql`: RCB01 및 테스트 계정 사전/사후 조회(읽기 전용).
- `sql/01_create_rcb01_fault_trigger.sql`: 테스트 prefix만 대상으로 한 실패 주입 트리거.
- `sql/02_rcb01_verify_results.sql`: 납부/거래 상태 집계 및 불일치 상세 조회.
- `sql/03_rcb01_trigger_status_and_cleanup.sql`: 트리거 확인과 명시적 제거 SQL.
- `evidence/`: 기존 결과 문서와 k6, Grafana, SQL 화면 캡처(원본 파일이 확보된 경우).

## 중요한 보존 한계

RCB01 테스트 데이터를 생성한 일회성 SQL 원본은 현재 저장소와 대화 첨부에서 찾지 못했습니다. 따라서 이 묶음은 당시 사용한 데이터 생성 SQL을 포함한다고 주장하지 않습니다. 기록으로 확인되는 준비 상태는 테스트 사용자 100명, AVAILABLE 연결 계좌 300개(사용자당 3개), 전체 테스트 거래 10,000건으로 보고되었지만, 10,000건의 계좌별 배분과 실제 INSERT 문은 보존된 근거가 없습니다. 과거 실행 중 `/tmp/account-sync-bank-seed.sql` 경로가 출력됐지만 파일 자체는 이 작업공간에서 찾지 못했습니다. 서버나 당시 사용한 셸에 파일이 남아 있는지 확인해 원본을 추가해야 동일 fixture를 정확히 재생성할 수 있습니다.

`sql/00_rcb01_fixture_inventory.sql`은 생성 SQL이 아니라 현재 DB의 수량/상태를 확인하는 쿼리입니다. 초기화가 필요하더라도 이 자료만으로 테스트 계정이나 거래를 삭제하지 마세요. 초기화 대상은 backend DB와 mock-bank DB 양쪽이며, 납부 의무/정산 상태까지 바뀌었을 수 있습니다.

## k6 설정과 실행 명령

### A. 정합성 재현: 단일 VU, 각 계좌 1회

당시 실행 명령:

```powershell
docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run `
  -e BASE_URL=http://host.docker.internal:18080 `
  -e USERS=1 `
  -e FIRST_USER=1 `
  -e ACCOUNTS_PER_USER=3 `
  -e RUN_LABEL=RCB01 `
  /scripts/account-sync-consistency.js
```

설정: `sync_once`, `per-vu-iterations`, VU 1개, VU당 1 iteration, 계좌 3개씩 순차 호출, `REQUEST_TIMEOUT=30s`, `MAX_DURATION=10m`, `gracefulStop=1m`, 재시도 없음. `BASE_URL`의 18080 포트는 Windows 호스트에서 배포 서버로 연 SSH 포워드였습니다. `TEST_PASSWORD`는 기본값 `loadtest1234!`; 비밀번호나 토큰은 결과 패키지에 포함하지 않았습니다.

### B. 정합성 스크립트: 100 VU 관측 실행

보존된 로그에 찍힌 실행 명령:

```powershell
docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run `
  -e BASE_URL=http://host.docker.internal:18080 `
  -e RUN_LABEL=RCB01 `
  /scripts/account-sync-consistency.js
```

그 로그의 스크립트 기본값은 `USERS=100`, `FIRST_USER=1`, `ACCOUNTS_PER_USER=3`, `REQUEST_TIMEOUT=30s`, 따라서 300 sync 요청이었습니다. setup에서 `READY run=RCB01, users=100, accounts=300`을 출력했습니다.

### C. 100 VU 반복 성능: account-sync-load.js

기본값에 맞춘 재실행 명령(당시 전체 명령줄은 로그에 보존되지 않아 아래는 스크립트 기본 설정을 명시한 재현 명령입니다):

```powershell
docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run `
  -e BASE_URL=http://host.docker.internal:18080 `
  -e VU_LEVELS=10,25,50,100 `
  -e RAMP_DURATION=30s `
  -e HOLD_DURATION=2m `
  -e RAMP_DOWN_DURATION=30s `
  -e STRICT_NEW_TRANSACTIONS=false `
  /scripts/account-sync-load.js
```

기본 단계는 각 target VU로 30초 ramp 후 2분 hold, target 순서 `10 → 25 → 50 → 100`, 마지막 30초 ramp-down입니다. 전체 scenario 길이는 10분 30초입니다. REPEAT 모드에서는 시드가 소진된 빈 동기화도 허용하며 신규 저장 0건은 오류가 아닙니다. 이 결과는 INSERT 성능이 아니라 주로 빈 sync 요청 성능을 측정한 것이므로 신규 거래 삽입 성능과 섞어 해석하지 마세요.

## 장애 주입과 제거

`sql/01_create_rcb01_fault_trigger.sql`은 `external_transaction_id LIKE 'RCB01-%'`인 행에 한정해 `PENDING → APPLIED` update를 거부합니다. 이 장애 주입이 있어야 단일 VU에서 REQUIRES_NEW 납부 커밋은 유지되고 바깥 트랜잭션은 실패하는 상황을 결정적으로 만들 수 있습니다. 일반적인 자연 부하 테스트용 트리거가 아닙니다.

### 서버 트리거 현재 상태

현재 제거 여부는 **확인되지 않았습니다**. 원격 서버를 읽기 전용 SSH로 확인하려 했지만 이 실행환경에서 `192.168.0.205:22` 접속이 거부되어 DB를 조회하지 못했습니다. 이전 결과 문서에도 트리거 제거 여부가 미확인이라고 기록돼 있습니다. 따라서 “이미 제거됨”이라고 단정할 근거가 없습니다.

VM의 MariaDB에서 다음을 실행해 확인하세요:

```sql
SHOW TRIGGERS FROM sai_wanjang LIKE 'bank_transaction';
```

결과의 `Trigger`가 `trg_lt_rcb01_reject_applied`라면 트리거가 남아 있습니다. 재현 실험을 종료했고 증거를 보관한 뒤에만 다음을 실행하세요:

```sql
DROP TRIGGER IF EXISTS sai_wanjang.trg_lt_rcb01_reject_applied;
SHOW TRIGGERS FROM sai_wanjang LIKE 'bank_transaction';
```

첫 SQL 결과는 존재 여부 확인이며, 두 번째는 삭제 후 동일 트리거가 없는지 재확인합니다. 삭제는 DB 객체만 제거하고 RCB01 불일치 거래 데이터는 남깁니다.

## 검증 SQL 사용 순서

1. 부하 전 `sql/00_rcb01_fixture_inventory.sql`을 실행해 테스트 계정/거래가 준비됐는지 확인합니다.
2. 결함 재현을 하려면 사전 DB 확인 후 `sql/01_create_rcb01_fault_trigger.sql`을 실행합니다.
3. k6 실행이 완전히 끝난 뒤 `sql/02_rcb01_verify_results.sql`을 실행합니다. 대상 불일치는 `CONFIRMED` 납부 기록과 `PENDING` 거래의 동일 bank_transaction_id 조인 결과입니다.
4. 결과 보존 후 트리거를 제거할 때만 `sql/03_rcb01_trigger_status_and_cleanup.sql`의 DROP 문을 실행합니다.
5. 수정 전후 속도 비교는 동일한 데이터 분포, VU 단계, duration, timeout, mock-bank 응답 조건으로 별도 실행하세요. 정합성 트리거 실험과 순수 성능 부하를 같은 지표로 비교하지 마세요.

## 당시 관측 결과

### RCB01 단일 VU, 3계좌 정합성 재현

- 준비: 사용자 1명, AVAILABLE 계좌 3개; 계좌별 sync 한 번, 재시도 없음.
- `sync` 요청: 3회 시도, HTTP 200은 0회, 세 건 모두 HTTP 500.
- sync 실패율: 100%; `consistency_sync_latency` 평균 64.55 ms, p95 94.85 ms, p99 98.72 ms, 최대 99.69 ms.
- 전체 HTTP: 5회 중 3회 실패, `http_req_failed=60%` (로그인/계좌 조회 setup 요청 2회 포함). 전체 duration 평균 78.89 ms, p95 130.64 ms, p99 136.83 ms, 최대 138.38 ms.
- DB 조인으로 `CONFIRMED + PENDING` 불일치 3건 확인: `RCB01-1-1`, `RCB01-1-2`, `RCB01-1-3`; 금액은 각 10,000, payment target은 SETTLEMENT. 상세 ID와 실행 캡처는 기존 결과 보고서 및 `evidence/`를 참고합니다.
- 이 결과는 조건부 DB trigger로 바깥 update를 실패시킨 의도적 장애 주입 재현입니다. 일반 트래픽에서 발생할 확률을 측정한 결과는 아닙니다.

### RCB01 100 VU 정합성 스크립트

보존 로그 기준 `USERS=100`, `accounts=300`, sync 시도 300건, sync HTTP 200 0건, 실패율 100%였습니다. sync latency 평균 29.91 s, 중앙값 29.96 s, p95 29.99 s, p99/max 30 s. 전체 HTTP 요청 500건 중 실패 300건(60%); setup 200건은 성공했습니다. 30초 timeout이 대량 발생해 이 결과는 불일치 수 재현 성공을 의미하지 않고, 정상적인 처리 속도 baseline으로도 쓰기 어렵습니다.

별도 보존된 고부하 시점의 서버 로그에는 HikariPool-1 획득 timeout이 기록됐습니다: `total=10, active=10, idle=0, waiting=81`, connection request timeout 30,000 ms. 이 로그는 커넥션 풀 고갈을 보여주지만 k6 요청별로 매핑된 로그가 아니므로 위 100 VU 결과와의 초 단위 상관관계는 확인되지 않았습니다.

### account-sync-load.js 반복 부하 결과 (REPEAT 모드)

보존된 로그에서 10분 30초 시나리오가 완료됐고 최대 VU는 100이었습니다. `http_reqs=20,987` (31.65 req/s), iterations 6,929, sync HTTP 200 6,869회, sync 실패 60회(0.86%). `sync_latency` 평균 1.67 s, p95 3.42 s, p99 28.72 s, 최대 30 s. 저장량 검증 6,911회 중 18회 실패(99.74%), 확인 가능한 요청은 `sync_empty=100%`, `sync_transactions_stored=0`이었습니다. 즉 고정 시드가 소진되어 빈 동기화 요청을 측정했습니다. InfluxDB flush 지연 경고도 있었으므로 결과 전송 지연과 앱 응답 지연을 구분하세요.

## 커밋 및 파일 출처

테스트 당시 서버 애플리케이션 SHA는 k6 로그에 기록되지 않았습니다. 현재 로컬 저장소에서 확인 가능한 HEAD는 `fa94a242134d393690fcafb49113f83720610709` (`feat(#161): 특약 스트리밍 이벤트...`, 2026-10-23)입니다. 이것은 지금 로컬 checkout의 HEAD이며, 2026-10-26 테스트 당시 서버 이미지와 같은 커밋이었다고 증명되지는 않습니다.

두 k6 JS는 현재 로컬 worktree에서 복사했습니다. 이 worktree에서 `account-sync-load.js`는 HEAD 대비 수정됐고 `account-sync-consistency.js`는 untracked 파일입니다. 따라서 SHA/스크립트 본문은 이 인수인계 묶음에 들어 있는 파일이 기준입니다. 당시 실행 서버/컨테이너에 사용된 정확한 소스 버전은 별도 이미지 digest 또는 배포 기록과 대조해야 합니다.

- `account-sync-consistency.js` SHA-256: `CBA4D16D3F25269328EEBBDFA9E8F15C416CF79196B445D65EF8D956FB76CCA0`
- `account-sync-load.js` SHA-256: `130029CBE8FFFF4950CEEA09471E40E4DD145122D277AD214E2DBD1D72D60470`

`git status`가 보여준 현재 HEAD의 변경 상태를 참고해 테스트 파일을 커밋에 귀속시키지 않았습니다. 이 handoff를 사용할 때는 두 k6 스크립트와 테스트 대상 backend 커밋 SHA를 새 테스트마다 함께 기록하세요.

## 수정 전후 속도 비교 기록표

| 실행 | backend SHA | k6 script SHA-256 | 데이터/매칭 건수 | VU·시간 설정 | sync 성공/실패 | 평균 | p95 | p99 | 최대 | 정합성 불일치 |
|---|---|---|---|---|---|---:|---:|---:|---:|---:|
| 수정 전 RCB01 단일 VU | 미기록(현재 checkout 후보만 확인) | 패키지 파일 기준 | 3계좌, 3회 확인 | 1 VU, 1회/계좌 | 0/3 | 64.55 ms | 94.85 ms | 98.72 ms | 99.69 ms | 3 |
| 수정 후 | 기입 | 기입 | 동일 조건 기입 | 동일 조건 기입 | 기입 | 기입 | 기입 | 기입 | 기입 | 기입 |
| 반복 부하 수정 전 | 미기록 | 패키지 파일 기준 | 빈 동기화(REPEAT) | 10→25→50→100, 10m30s | 6,869/60 | 1.67 s | 3.42 s | 28.72 s | 30 s | 해당 없음 |
| 반복 부하 수정 후 | 기입 | 기입 | 같은 조건 기입 | 같은 조건 기입 | 기입 | 기입 | 기입 | 기입 | 기입 | 해당 없음 |

성능 개선을 비교할 때는 특히 `sync_latency`와 성공 요청만의 p95/p99를 비교하고, 실패율/타임아웃, `sync_transactions_stored`, DB 커넥션 대기와 CPU/IO 조건도 같이 기록해야 합니다. 빈 동기화와 거래 100건 매칭 동기화는 서로 다른 작업량입니다.

## 서버에서 원본 SQL·배포 SHA·트리거 상태 확인

2026-10-28 재조회에서 이 실행환경의 SSH 연결이 timeout되어 서버 명령을 실행하지 못했습니다. 따라서 파일 존재, 실제 배포 커밋, 트리거 제거 여부는 아직 모두 서버 측 미확인입니다.

### 1) Seed SQL 원본과 초기화 범위

VM에서:

```bash
sudo test -f /tmp/account-sync-bank-seed.sql && sudo stat /tmp/account-sync-bank-seed.sql
sudo sha256sum /tmp/account-sync-bank-seed.sql
sudo grep -nEi '^(USE|DELETE|TRUNCATE|DROP|CREATE|INSERT|UPDATE|SET)' /tmp/account-sync-bank-seed.sql | head -200
```

파일이 있다면 내용을 먼저 검토합니다. 파일을 지금 확보해도 SQL 실행 전까지는 변경이 없습니다. 다음을 확인한 뒤에만 seed/reset을 실행하세요.

- `USE` 또는 명시적 schema가 `sai_wanjang`과 `mock_bank`를 올바르게 지정하는가.
- 삭제/초기화 조건이 테스트 사용자/계좌/RCB01 prefix만 대상으로 하는가.
- 재실행 가능한지, 중복 거래나 잔존 `payment_record`/정산 상태를 남기지 않는가.
- 계좌·거래 생성 및 커서 설정 순서가 sync 스크립트의 전제와 일치하는가.

이 SQL 원본을 이 대화에 첨부해 주면, 두 DB의 스키마에 맞춰 초기화 범위와 실행 순서를 검토해 패키지에 추가할 수 있습니다. 원본을 읽기 전에는 안전한 정확 초기화 SQL을 단정할 수 없습니다.

### 2) 실제 배포 backend commit 확인

VM에서 checkout과 실행 컨테이너 정보를 따로 수집합니다:

```bash
cd /opt/sai-backend/sai-backend-v2
git rev-parse HEAD
git status --short
sudo docker inspect sai-backend-app --format 'image={{.Image}} configImage={{.Config.Image}} labels={{json .Config.Labels}}'
sudo docker image inspect "$(sudo docker inspect sai-backend-app --format '{{.Image}}')" --format 'id={{.Id}} created={{.Created}} labels={{json .Config.Labels}}'
```

`git rev-parse HEAD`는 서버 checkout의 commit이지 실행 중인 이미지의 commit이라는 보장은 없습니다. 이미지 label에 revision/SHA가 없으면 배포 파이프라인의 이미지 digest↔commit 기록이 필요합니다. `/actuator/info`에서 git/build 정보를 제공하는 설정이 있으면 그 값도 보조 증거로 기록하세요.

### 3) 트리거 존재와 제거 확인

VM에서:

```bash
sudo docker exec -it sai-backend-db mariadb -u root -p sai_wanjang
```

MariaDB prompt에서:

```sql
SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = 'sai_wanjang'
  AND TRIGGER_NAME = 'trg_lt_rcb01_reject_applied';
```

행이 있으면 트리거가 남아 있습니다. 테스트가 끝났고 결과를 보존했으면 제거한 뒤 같은 쿼리를 다시 실행합니다:

```sql
DROP TRIGGER IF EXISTS sai_wanjang.trg_lt_rcb01_reject_applied;
SELECT TRIGGER_NAME
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = 'sai_wanjang'
  AND TRIGGER_NAME = 'trg_lt_rcb01_reject_applied';
```

두 번째 SELECT가 0행이면 해당 이름의 트리거는 제거된 상태입니다. 단일 VU 장애 재현은 트리거를 켜고 실행해야 하며, 정상 성능 비교는 트리거를 제거한 별도 실행으로 해야 합니다.
