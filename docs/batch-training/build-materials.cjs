const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../..');
const base = 'src/main/java/org/teamsai/saibackend/';
const esc = s => String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const annotations = require('./code-notes.cjs');
const code = (file, start, end) => {
  const lines = fs.readFileSync(path.join(root, base, file), 'utf8').split(/\r?\n/);
  if (start < 1 || start > lines.length || !lines.slice(start-1,end).some(l=>l.trim())) throw new Error(`Invalid source range: ${file}:${start}`);
  const rules = annotations[file.split('/').pop()] || [];
  const excerpt = lines.slice(start-1,end).map((l,i)=>{
    const notes = rules.filter(([pattern])=>l.includes(pattern)).map(([,text])=>`<span class="explain">     // [설명] ${esc(text)}</span>`);
    return [...notes,`<span class="ln">${start+i}</span>${esc(l)}`].join('\n');
  }).join('\n');
  return `<details class="source" open><summary>주석을 붙인 실제 코드 · ${esc(file.split('/').pop())}:${start}</summary><p class="filepath">${esc(base + file)}<br>줄 번호는 원본 위치입니다. [설명] 주석은 교육자료에만 추가했습니다.</p><pre><code>${excerpt}</code></pre></details>`;
};
const box = (title, text='', cls='') => `<div class="node ${cls}"><strong>${title}</strong>${text ? `<small>${text}</small>`:''}</div>`;
const flow = (label, nodes) => `<div class="flow" role="group" aria-label="${label}">${nodes.map((n,i)=>(i?'<span class="arrow" aria-hidden="true">→</span>':'')+box(n[0],n[1]||'',n[2]||'')).join('')}</div>`;
const callout = (title, text) => `<aside><strong>${title}</strong><p>${text}</p></aside>`;
const table = (heads, rows) => `<div class="tablewrap"><table><thead><tr>${heads.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${r.map(c=>`<td>${c}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
const sections=[];
const add=(id,kicker,title,lead,body)=>sections.push({id,kicker,title,lead,body});

add('why','01 / WHY','왜 배치가 필요한가','사용자가 화면을 열지 않아도, 시간이 지나면 해야 할 일이 생깁니다.',`
<div class="cards three">${box('새 거래가 들어왔다','은행 내역을 가져와 상환·정산에 반영')}${box('납부일이 다가왔다','D-3 · D-1 · 당일에 사용자 알림 생성')}${box('기한이 지났다','연체를 표시하고 장기 미납을 정리')}</div>
${flow('요청 처리와 배치의 역할',[['사용자 요청','계약·정산을 생성한다'],['업무 DB','기한·상태·계좌를 보관한다'],['정기 배치','대상을 찾아 시간에 따른 후속 처리를 한다']])}
<p>배치는 정해진 범위의 데이터를 반복 처리하는 작업입니다. 스케줄은 실행 시점을 정하고, Spring Batch는 실행 단위·이력·처리 정책을 제공합니다. 현재 프로젝트는 @Scheduled로 자정 실행을 예약하고, Chunk Step에 계좌별 반복 처리를 설정합니다.</p>
${callout('이 프로젝트에서의 가치','기한 처리를 사용자 접속에 의존하지 않고, 계좌별 동기화와 실패 재처리를 일정한 정책으로 수행합니다. 재기동 후 정기정산 생성은 누락 회차를 따라잡고, 리마인드는 실행 기준일의 D-3·D-1·당일 대상만 조회합니다.')}`);

add('concepts','02 / CONCEPTS','Job · Step · 실행 이력','Job은 업무 하나의 이름이고, Step은 그 업무를 수행하는 처리 단계입니다.',`
<div class="hierarchy">${box('Job · transactionSyncJob','은행거래 동기화라는 업무')}<div class="down">↓ 이 프로젝트는 10개 Job 모두 Step 1개</div>${box('Step · transactionSyncStep','Reader / Processor / Writer와 처리 정책')}<div class="down">↓ 실제 로직 위임</div>${box('도메인 서비스','동기화 · 매칭 · 저장 · 상태 변경')}</div>
${table(['용어','쉽게 설명하면','현재 코드에서'],[
['Job','실행할 업무의 설계도','은행거래 동기화, 정산 연체 등 10개'],
['Step','Job 안의 처리 단계','현재 각 Job에 1개씩 연결'],
['JobInstance','Job 이름과 식별 파라미터로 구분하는 논리적 작업','baseDate와 매번 바뀌는 runId를 전달'],
['JobExecution / StepExecution','실제로 실행한 이력과 결과','상태, 시작·종료 시각, read/write/skip 집계'],
['JobParameters','이번 실행에 넣는 입력값','baseDate: 업무 기준일 / runId: 실행 구분'],
['JobRepository','배치 실행 기록을 저장하는 곳','JDBC 기반 저장소. 업무 데이터 저장소와 역할이 다름']])}
<h3>현재는 왜 Step을 하나씩 연결했을까?</h3><p>각 JobConfig는 .start(해당 Step) 뒤에 .build()를 호출합니다. 거래 Job의 Step은 계좌 조회·동기화·매칭을 하나의 반복으로 묶고, Tasklet Job의 Step은 업무 서비스 한 절차를 호출합니다. 현재 코드의 실행 상태와 집계 경계가 이 한 단계에 모입니다.</p>
${flow('현재 Job의 구성',[['Job','transactionSyncJob'],['Step 1개','transactionSyncStep'],['Step 내부 반복','계좌 조회 → 동기화·매칭 → 로그']])}
<h3>여러 Step은 언제 사용하는가?</h3><p>독립적인 입력·출력, 단계별 실행 결과, 실패 후 재시작 경계가 필요한 업무라면 하나의 Job 안에 여러 Step을 연결할 수 있습니다. 예를 들어 파일 검증 → 데이터 적재 → 결과 보고를 단계별로 나누면 실패한 단계를 실행 이력에서 구분하기 쉽습니다. 재시작 시 실행 여부는 Step 설정과 이전 실행 상태로 결정합니다.</p>
${flow('다중 Step 설계 예시',[['Job','파일 처리 · 설계 예시'],['검증 Step','파일 형식·필수값 검사'],['적재 Step','유효 데이터 저장'],['보고 Step','처리 결과 작성']])}
<p class="filepath">다중 Step 그림은 설계 개념을 설명하는 예시입니다. 현재 프로젝트의 각 Job은 단일 Step으로 구성되어 있습니다.</p>`);

add('architecture','03 / ARCHITECTURE','전체 아키텍처','같은 백엔드 안에서 실행 시점, 실행 관리, 업무 처리를 분리합니다.',`
<div class="architecture">
${box('Spring Scheduler · 매일 00:00 Asia/Seoul','@Scheduled가 실행 시점 결정','accent')}
<div class="down">↓ Scheduler 진입 시 DB 락 획득</div>
${box('ShedLock · JDBC / DB 시간','같은 락 이름의 스케줄이 여러 Pod에서 중복 실행되는 것을 억제')}
<div class="cards two">${box('DailyBatchPipelineScheduler','거래 동기화 → 매칭 재시도 → 정기 생성 → 상환 연체 → 정산 연체 → 정산 상각 → 상환 상각')}${box('개별 Scheduler 3개','상환 리마인드 / 정산 리마인드 / 정산 방치 감지<br>각각 별도 락 이름과 실행 진입점')}</div>
<div class="down">↓ baseDate + runId → JobOperator.start(job, params)</div>
${box('Job → Step','Chunk 2개 / Tasklet 8개','accent')}
<div class="cards three">${box('업무 서비스 → 업무 DB','JPA · 벌크 UPDATE · 잠금 · 트랜잭션')}${box('거래 조회 → MockBankClient','외부 은행 모사 API 연동')}${box('알림 저장 / Slack','사용자 Notification / 운영자 메시지')}</div>
<div class="cards two">${box('JobRepository','Job·Step 실행 이력 저장')}${box('LoggingJobExecutionListener','시작 로그 / 종료 결과·건수·소요시간 Slack')}</div></div>
${callout('선후 관계를 정확히 읽기','7개는 코드의 start() 호출 순서입니다. 나머지 3개와의 선후 관계는 명시되어 있지 않습니다. 파이프라인은 앞 Job의 성공을 조건으로 다음 Job을 실행하지 않으며, 실행 요청의 예외도 잡고 다음 호출을 계속합니다. 실제 완료 순서·동시성은 실행기 설정까지 보아야 합니다.')}
${code('domain/batch/orchestration/DailyBatchPipelineScheduler.java',39,63)}`);

add('modes','04 / PROCESSING','Chunk와 Tasklet의 차이','선택 기준은 데이터 처리 단위, 반복 주체, 실패 제어 방식입니다.',`
<div class="cards two"><article class="panel"><h3>Chunk · 항목 중심 반복</h3>${flow('Chunk 처리',[['Reader','계좌 1개씩 읽기'],['Processor','계좌별 업무 수행'],['Writer','최대 50개 결과 전달']])}<p>프레임워크가 읽기·처리·쓰기 반복과 chunk 경계를 관리합니다. 이 프로젝트는 계좌 50개로 설정합니다. chunk(50)은 Writer에 전달할 계좌 DTO 묶음의 크기와 Step의 처리 경계를 정합니다.</p></article><article class="panel"><h3>Tasklet · 업무 절차 호출</h3>${flow('Tasklet 처리',[['Tasklet','기준일 해석'],['Service','조회·반복·상태 갱신'],['FINISHED','Step 종료']])}<p>개발자가 서비스 내부의 반복·페이징·예외 집계를 구성합니다. 한 번 호출해 수천 건을 처리할 수도 있습니다. Tasklet도 CONTINUABLE을 반환해 반복할 수 있지만, 현재 8개는 FINISHED로 끝납니다.</p></article></div>
${table(['비교','Chunk','현재 Tasklet'],[['반복 주체','Spring Batch의 항목 처리 루프','업무 서비스의 반복문·벌크 쿼리'],['오류 정책','Step의 retry / skip 설정','서비스별 catch·집계·임계값'],['이력 집계','항목 read/write/skip 중심','contribution으로 처리 건수를 직접 반영'],['트랜잭션','기본적으로 chunk 경계. 내부 서비스 전파 설정은 별도','Step 트랜잭션 + 내부 서비스의 REQUIRES_NEW 등'],['이 프로젝트의 용도','계좌별 동일 작업 2개','벌크 갱신·회차 생성·알림·상각 8개']])}
<h3>Reader · Processor · Writer에 각각 무엇을 넣는가?</h3>
${table(['구성','일반적인 책임','현재 거래 동기화 Step의 실제 내용','입출력'],[
['Reader','처리할 입력 항목을 하나씩 공급. SQL·조회 조건·정렬·페이지 크기 설정','LinkedAccountSyncTargetReaderFactory가 AVAILABLE 계좌를 linkedAccountId 순으로 조회','DB → LinkedAccountSyncTargetDTO(계좌 ID, 사용자 ID)'],
['Processor','항목별 변환·검증·업무 서비스 호출. 반환값을 Writer에 전달','syncAndMatch(userId, linkedAccountId, true)를 호출해 거래 저장과 자동 매칭 후 같은 DTO 반환','계좌 DTO → 계좌 DTO'],
['Writer','처리된 항목 묶음의 저장·전송 등 최종 출력 처리','noOpWriter가 묶음의 계좌 수와 ID를 로그에 기록. 업무 저장은 Processor가 호출한 서비스에서 실행','계좌 DTO 묶음 → 완료 로그']])}
${flow('계좌 DTO가 이동하는 경로',[['Reader','계좌 A의 ID + 사용자 ID'],['Processor','A의 거래 동기화·매칭 후 A 반환'],['Writer','최대 50개 계좌 DTO를 받아 로그']])}
${code('domain/batch/transaction/reader/LinkedAccountSyncTargetReaderFactory.java',22,39)}
${code('domain/batch/transaction/sync/TransactionSyncJobConfig.java',69,92)}
<h3>Tasklet Step은 어떻게 실행되는가?</h3><p>현재 Tasklet Step은 기준일 해석 → 서비스 호출 → 처리·실패 집계 → FINISHED 반환 순서로 실행됩니다. 대상 조회와 반복은 해당 서비스에서 담당합니다.</p>
${code('domain/batch/settlement/recurring/RecurringSettlementGenerationJobConfig.java',34,68)}
${callout('꼭 구분할 세 숫자','Reader pageSize 50 = DB에서 가져오는 페이지 크기 / chunk(50) = 프레임워크의 묶음 처리 경계 / 상각의 500개 분할 = 서비스 내부 쿼리 묶음. 각 값은 조회·프레임워크 처리·쿼리 분할의 경계를 각각 설정합니다. REQUIRES_NEW에서 이미 커밋한 변경은 바깥 트랜잭션이 롤백되어도 유지되고, 외부 전송 결과는 수신 시스템에 남습니다.')}`);

const jobs=[
['transactionSyncJob','Chunk 50','AVAILABLE 계좌마다 동일한 동기화·매칭을 수행하고 특정 은행 조회 예외를 재시도·스킵하기 적합'],
['bankTransactionRetryJob','Chunk 50','동일 계좌 Reader를 재사용하고 계좌별 재매칭 실패를 격리하기 적합'],
['recurringSettlementGenerationJob','Tasklet','정기설정 → 누락 회차 반복 → 회차별 생성이라는 중첩 절차를 서비스가 관리'],
['repaymentScheduleOverdueJob','Tasklet','조건에 맞는 스케줄을 벌크 UPDATE 한 번으로 갱신'],
['settlementOverdueJob','Tasklet','정산별 기준일 판정 후 참여자 결제의무를 정산 단위 트랜잭션으로 변경'],
['repaymentDueReminderJob','Tasklet','D-3·D-1·당일 조회, 채무자 확인, 개별 알림·실패 임계값을 한 절차로 관리'],
['settlementDueReminderJob','Tasklet','정산 → 참여자 → 최신 미납 의무를 탐색해 알림을 생성하는 다단계 절차'],
['settlementAbandonmentJob','Tasklet','감지·중복 기록·미전송 Slack 재전송이라는 두 단계 절차'],
['settlementWriteOffJob','Tasklet','상각 후보 조회·상각·관련 정산 자동종결을 연결'],
['repaymentWriteOffJob','Tasklet','후보 ID 조회 후 조건부 벌크 UPDATE를 수행']];
add('choices','05 / DESIGN CHOICES','10개 Job은 왜 이 방식을 쓰는가','방식은 코드에서 확인한 사실입니다. 아래 선택 이유는 구현 구조에 근거한 해석이며, 개발 당시 의사결정을 단정하지 않습니다.',table(['Job','방식','이 구조에 맞는 이유'],jobs)+callout('Tasklet은 서비스의 업무 절차를 실행합니다','오히려 복합 업무 절차를 기존 서비스에 위임할 때 간결합니다. 데이터가 커져 대상별 체크포인트·재시작 제어가 필요해지면 일부 Tasklet을 Chunk로 바꾸는 선택도 가능합니다.'));

add('transactions','06 / FLOW 1','거래 동기화 + 매칭 재시도','신규 거래를 반영하고, 이전에 해결하지 못한 거래를 다시 검사합니다.',`
<h3>① transactionSyncJob · Chunk</h3>${flow('거래 동기화 흐름',[['AVAILABLE 계좌','ID 순 · page 50'],['syncTransactions','마지막 동기화 거래 ID 이후 조회'],['거래 저장 + 커서 전진','BankTransactionPersistenceService'],['자동 매칭','BankMatchingService.execute'],['완료 로그','Writer는 no-op']])}
<h3>② bankTransactionRetryJob · Chunk</h3>${flow('매칭 재시도 흐름',[['같은 계좌 Reader','공통 Factory 재사용'],['재시도 거래 조회','상태·횟수 정책'],['후보 삭제 + PENDING','이전 결과 초기화'],['자동 매칭 재실행','새 은행 조회는 하지 않음'],['소진 시 Slack','FAILED 5 / UNMATCHED·NEEDS_CHECK 14']])}
<div class="cards two">${box('핵심 1 · 두 종류의 재시도','동기화의 일시적 은행 조회 오류: retryLimit(2), 해당 예외 skipLimit(50).<br>매칭 업무 재시도: 거래 상태별 누적 횟수 정책. 서로 별개입니다.')}${box('핵심 2 · 실제 업무는 Processor','두 Job 모두 Processor에서 서비스가 저장·매칭을 수행합니다. Writer는 완료 로그만 남깁니다. writeCount는 Writer로 전달된 처리 계좌 수를 집계합니다.')}</div>
${code('domain/batch/transaction/sync/TransactionSyncJobConfig.java',49,62)}
${code('domain/batch/transaction/sync/TransactionSyncJobConfig.java',69,80)}
${code('domain/matching/service/BankTransactionRetryService.java',25,53)}`);

add('recurring','07 / FLOW 2','정기정산 회차 생성','반복 설정을 실제 정산·참여자·결제의무 데이터로 바꿉니다.',`
${flow('정기정산 생성 흐름',[['활성 설정 조회','기준일 유효 범위'],['최근 회차·회차 수','시작일 + 반복 규칙'],['생성 가능일 검사','오늘 이하 / 종료일 이내'],['최근 회차 잠금','동시 생성 방어'],['회차 생성','정산 + 참여자 + 의무 + 계좌'],['다음 회차 반복','설정당 최대 31개']])}
<div class="cards three">${box('날짜 계산','DAILY / WEEKLY / MONTHLY / YEARLY<br>월·년의 없는 날짜는 말일로 조정')}${box('금액 구성','균등 분배면 다시 계산<br>개별 분배면 기존 금액 승계')}${box('회차별 독립 커밋','generateOneCycle의 REQUIRES_NEW<br>뒤 회차 실패가 이전 회차를 지우지 않음')}</div>
${callout('예시','매일 생성해야 하는 정산이 며칠간 누락되었다면 오늘 회차 하나만 만들지 않고 누락된 회차를 순서대로 따라잡습니다. 기존 회차나 ACTIVE 참여자가 없으면 생성하지 않습니다. 처리 건수는 성공 처리한 정기설정 수를 집계합니다.')}
${code('domain/batch/settlement/recurring/RecurringSettlementGenerationService.java',79,117)}
${code('domain/settlement/service/RecurringSettlementCycleService.java',42,64)}`);

add('overdue','08 / FLOW 3','상환 연체 + 정산 연체','기한 경과라는 조건은 같지만, 연체를 저장하는 필드가 다릅니다.',`
<h3>④ 상환 · Tasklet</h3>${flow('상환 연체 흐름',[['PENDING 스케줄','dueDate < baseDate'],['벌크 UPDATE','기존 상태 조건 포함'],['OVERDUE','스케줄 상태 변경']])}
<h3>⑤ 정산 · Tasklet</h3>${flow('정산 연체 흐름',[['IN_PROGRESS 정산','100개씩 페이지 조회'],['기준일 판정','SHARED: dueDate / RECURRING: cycleDate'],['ACTIVE 참여자','활성 미납·부분납부 의무'],['overdueSince 기록','기준일 다음 날 00:00']])}
${callout('정산 자체가 OVERDUE가 되지는 않습니다','정산은 IN_PROGRESS로 남고 PaymentObligation에 연체 시작일을 기록합니다. 정산별 REQUIRES_NEW로 갱신하며 개별 실패는 집계하고 계속합니다. 당일은 연체가 아니며 다음 날부터 대상입니다.')}
${code('domain/contract/service/RepaymentScheduleService.java',115,131)}
${code('domain/settlement/service/OverdueSettlementUpdateService.java',24,41)}`);

add('reminders','09 / FLOW 4','상환 리마인드 + 정산 리마인드','D-3 · D-1 · 당일의 미해결 대상에게 사용자 알림을 저장합니다.',`
<h3>⑥ 상환 · Tasklet</h3>${flow('상환 알림 흐름',[['날짜 3개 계산','baseDate + 3 / + 1 / 당일'],['PENDING 스케줄','상환 예정일로 조회'],['계약의 채무자','없으면 skipped'],['알림 생성','스케줄 ID 참조 / 중복 확인']])}
<h3>⑦ 정산 · Tasklet</h3>${flow('정산 알림 흐름',[['IN_PROGRESS 정산','dueDate / cycleDate'],['D-3·D-1·당일 판정','ACTIVE 참여자 조회'],['최신 활성 의무','미해결 결제의무만 선별'],['알림 생성','의무 ID 참조 / 중복 확인']])}
<div class="cards two">${box('개별 알림 트랜잭션','알림 하나씩 독립 트랜잭션으로 저장합니다. 일부 실패가 이미 저장한 다른 알림을 모두 취소하지 않도록 합니다.')}${box('실패 정책의 차이','상환은 실패 10건 초과 시 예외.<br>정산은 정산 실패·알림 실패를 집계하고 다음 대상 처리.')}</div>
${callout('알림 저장과 외부 발송을 구분','이 흐름이 직접 하는 일은 Notification 저장입니다. 여기서 이메일·문자 발송까지 완료했다고 설명하지 않습니다. createIfAbsent 계열이 중복 생성을 방어하므로 처리 집계가 신규 알림 수와 항상 같지는 않습니다.')}
${code('domain/batch/repaymentschedule/reminder/ReminderNotificationSender.java',24,51)}
${code('domain/settlement/support/SettlementReminderSender.java',75,84)}`);

add('abandonment','10 / FLOW 5','정산 장기방치 감지','진행 중 정산이 기준일에서 3일 이상 지났는지 확인하고 운영자에게 알립니다.',`
${flow('방치 감지 흐름',[['IN_PROGRESS 정산','100개씩 조회'],['기준일 + 3 ≤ baseDate','dueDate / cycleDate'],['감지 기록 생성','정산 ID + 기준일로 중복 방어'],['PENDING 전송','새 감지 + 이전 미전송'],['Slack 성공 → SENT','실패 → PENDING 유지']])}
<div class="cards two">${box('상태 변경 대신 운영 감시','정산 취소·종결·상각을 하지 않습니다.<br>최근 접속이나 수정 시각도 판정에 사용하지 않습니다.')}${box('기록과 전달을 분리','전송 실패를 DB에 남겨 다음 실행에서 다시 보낼 수 있습니다. 감지에 실패해도 기존 PENDING 전달을 시도하는 구조입니다.')}</div>
${callout('정확히 한 번 전송을 보장하지는 않음','Slack이 메시지를 받은 직후 DB 커밋 전에 장애가 나면 다음 실행에서 중복 메시지가 갈 수 있습니다. 코드도 at-least-once 전달임을 명시합니다.')}
${code('domain/batch/settlement/abandonment/SettlementAbandonmentNotifier.java',20,35)}`);

add('writeoff','11 / FLOW 6','정산 상각 + 상환 상각','장기 연체 대상을 WRITTEN_OFF로 전환하며, 납부 완료와는 구별합니다.',`
<h3>⑨ 정산 · Tasklet</h3>${flow('정산 상각 흐름',[['ACTIVE 미납·부분납부','overdueSince ≤ 30일 전 00:00'],['잠금 + 상태 재확인','의무를 500개씩 조회'],['WRITTEN_OFF','별도 트랜잭션으로 처리'],['관련 정산 검사','모든 의무 해결 시 CLOSED']])}
<h3>⑩ 상환 · Tasklet</h3>${flow('상환 상각 흐름',[['OVERDUE 스케줄','dueDate ≤ baseDate - 30일'],['후보 ID 분할','500개씩 조건부 UPDATE'],['WRITTEN_OFF','이 Job의 계약 종결 호출 없음']])}
${callout('30일의 출발점이 다릅니다','정산은 overdueSince, 상환은 dueDate를 기준으로 합니다. 예를 들어 기준일·상환일이 10/1이면 상환은 10/31에 대상이 될 수 있고, 정산의 overdueSince가 10/2라면 11/1에 대상이 됩니다.')}
${callout('500개 분할 ≠ 500개마다 커밋','두 상각 경로 모두 서비스 내부에서 목록을 500개씩 나눕니다. 반복 전체를 감싼 REQUIRES_NEW 메서드이므로 전체 목록 처리가 이 메서드의 트랜잭션 안에서 진행됩니다. 정산 자동종결은 정산별 별도 트랜잭션입니다.')}
${code('domain/batch/writeoff/SettlementWriteOffBatchService.java',28,59)}
${code('domain/batch/writeoff/RepaymentWriteOffTransactionExecutor.java',22,30)}`);

add('infra','12 / INFRASTRUCTURE','JobConfig · Listener · 공통 인프라','업무마다 달라야 하는 규칙과, 모든 Job에 일관되어야 하는 운영 규칙을 분리합니다.',`
${flow('설정과 실행 관계',[['JobConfig','Job·Step Bean 조립'],['Step','처리 방식·오류 정책'],['Service','업무 규칙 실행'],['Listener','시작·종료 관찰']])}
${table(['구성요소','무엇을 하는가','왜 필요한가'],[
['JobConfig / @Configuration + @Bean','Job 이름, Step 연결, Listener, 트랜잭션·재시도·스킵을 조립','업무 로직과 실행 정책을 분리하고 Spring이 실행 객체를 관리'],
['LoggingJobExecutionListener','beforeJob 로그 / afterJob 결과·소요시간·집계 Slack','10개 Job의 관측 기준을 통일. 업무 로직에 알림 코드를 반복하지 않음'],
['BaseSkipListener','read/process/write 스킵 원인 로그','어떤 대상을 왜 건너뛰었는지 추적'],
['BatchInfraConfig','@EnableJdbcJobRepository','프로세스 메모리 외부에 실행 상태와 이력 보관'],
['SchedulerLockConfig / ShedLock','DB 시간으로 스케줄 락 관리','여러 Pod가 같은 스케줄을 동시에 시작하는 것을 억제'],
['AbstractDailyBatchScheduler + DailyJobParameters','개별 3개 스케줄의 실행·파라미터 공통화','실행 규칙의 중복을 줄임. 7개 파이프라인은 별도 생성 코드 사용'],
['JobDisplayNames','Job 이름 → 한글 표시명','운영 메시지 가독성 통일'],
['LinkedAccountSyncTargetReaderFactory','두 거래 Job의 AVAILABLE 계좌 조회 재사용','대상 조건·정렬·페이지 크기 일치']])}
${code('domain/batch/transaction/sync/TransactionSyncJobConfig.java',30,37)}
${code('domain/batch/common/listener/LoggingJobExecutionListener.java',23,47)}
${callout('공통화의 경계','두 거래 JobConfig는 chunk(50)을 직접 지정합니다. Reader Factory는 PAGE_SIZE=50을 사용하고 상각 서비스는 목록을 500개씩 나눕니다. 공통 클래스를 만들었다는 사실보다 실제 연결 지점을 확인해야 합니다.')}`);

add('operations','13 / TAKEAWAYS','팀이 꼭 기억할 운영 포인트','성공 상태, 처리 건수, 중복 실행 방어의 범위를 함께 읽습니다.',`
${table(['오해하기 쉬운 말','정확한 설명'],[
['COMPLETED니까 모두 성공했다','일부 Tasklet은 실패를 집계한 뒤 FINISHED. 상태와 skip·failed 집계를 함께 확인'],
['처리 100건이면 납부 100건이다','거래 Job은 계좌 수, 정기 생성은 성공한 정기설정 수, 상각은 의무·스케줄 수'],
['ShedLock이면 모든 중복이 없다','같은 락 이름의 스케줄 진입을 억제. 수동 실행·락 만료·외부 전송 중복은 별도 고려'],
['같은 날짜 실행은 자동 차단된다','runId가 매번 바뀌므로 새 실행 가능. 업무 상태 조건·알림 중복 확인·잠금 필요'],
['리마인드는 생성 이후에 실행된다','별도 스케줄 3개와 파이프라인 사이 선후 관계가 없음'],
['커서·DB 롤백이면 API 호출도 취소된다','외부 호출은 DB 트랜잭션으로 되돌릴 수 없음'],
['baseDate가 모든 거래 조회 범위다','두 거래 Job은 baseDate로 과거 거래를 재현하지 않음. 계좌 커서와 재시도 정책 사용']])}
<div class="cards three">${box('시간대','cron은 Asia/Seoul 지정.<br>baseDate는 LocalDate.now()이므로 JVM 기본 시간대도 확인')}${box('생성 순서','동기화·재매칭 이후 정기 회차 생성.<br>같은 실행에서 뒤늦게 생성된 회차는 앞선 매칭에서 다룰 수 없음')}${box('개발용 실행','dev 프로필 /dev/batch<br>baseDate 지정 가능. 운영 스케줄 락 경로와 다름')}</div>
${callout('현재 코드 분석의 범위','2026-10-27 로컬 소스 기준입니다. 방식 선택 이유는 구조에 기반한 설명입니다. 실행 환경의 실제 스레드 수·배포 시간대·처리량은 배포 설정과 운영 지표에서 확인해야 합니다. 업무 코드는 수정하지 않았습니다.')}
<p class="closing">시간이 만드는 업무를 자동 처리하고,<br><b>실행 정책·업무 규칙·운영 관측을 분리한다.</b></p>`);

const style=`:root{--bg:#f2f5fb;--paper:#fff;--ink:#192843;--muted:#5c6d85;--line:#dce4ef;--accent:#245bdd;--soft:#edf3ff}*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:35px}body{margin:0;color:var(--ink);background:var(--bg);font:16px/1.75 "Segoe UI","Malgun Gothic",sans-serif}header{background:#132b50;color:white;padding:65px max(7vw,24px) 52px}header p{max-width:780px;color:#c9d7ed}h1{font-size:clamp(30px,4vw,52px);line-height:1.25;margin:16px 0}h2{font-size:30px;line-height:1.4;margin:7px 0 12px}h3{font-size:19px;margin:26px 0 12px}p{margin:12px 0}.eyebrow{letter-spacing:.12em;font-size:12px;font-weight:700;color:#91b6ff}.layout{display:grid;grid-template-columns:235px minmax(0,1fr);max-width:1560px;margin:auto}nav{position:sticky;top:0;align-self:start;height:100vh;overflow:auto;padding:28px 18px;border-right:1px solid var(--line)}nav a{display:block;color:var(--muted);text-decoration:none;font-size:13px;padding:8px 10px;border-radius:7px;line-height:1.5}nav a:hover,nav a.active{background:#dfeaff;color:#194fb9}main{min-width:0;padding:30px 38px 60px}section{background:var(--paper);border:1px solid var(--line);border-radius:16px;padding:34px;margin-bottom:26px;scroll-margin-top:25px}.kicker{font-size:12px;letter-spacing:.1em;font-weight:700;color:var(--accent)}.lead{font-size:18px;color:var(--muted);margin-bottom:24px}.cards{display:grid;gap:14px;margin:20px 0}.two{grid-template-columns:repeat(2,minmax(0,1fr))}.three{grid-template-columns:repeat(3,minmax(0,1fr))}.node{background:var(--soft);border:1px solid #cfdcf7;border-radius:11px;padding:17px;min-width:0;text-align:center;overflow-wrap:anywhere}.node strong{display:block;font-size:15px}.node small{display:block;font-size:13px;color:var(--muted);line-height:1.6;margin-top:7px}.accent{background:#245bdd;color:white}.accent small{color:#e0eaff}.flow{display:flex;align-items:stretch;gap:8px;margin:20px 0;flex-wrap:wrap}.flow .node{flex:1 1 135px}.arrow{align-self:center;color:var(--accent);font-size:23px;font-weight:700}.down{text-align:center;color:var(--muted);font-size:13px;padding:9px}.hierarchy{max-width:700px;margin:20px auto}.architecture{padding:20px;border:1px dashed #b9c9e5;border-radius:12px}.architecture>.cards{margin:15px 0}.panel{border:1px solid var(--line);border-radius:12px;padding:18px;min-width:0}.panel h3{margin-top:0}.panel .flow{display:block}.panel .arrow{display:block;text-align:center;transform:rotate(90deg)}aside{border-left:4px solid var(--accent);background:#f0f5ff;padding:17px 20px;margin:23px 0;border-radius:0 9px 9px 0}aside p{font-size:14px;margin-bottom:0}.tablewrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:14px;margin:15px 0}th{text-align:left;background:#f0f4fa;color:#334e73}th,td{padding:13px 14px;border-bottom:1px solid var(--line);vertical-align:top;overflow-wrap:anywhere}td:first-child{font-weight:600}details{margin:16px 0;border:1px solid var(--line);border-radius:9px;overflow:hidden}summary{cursor:pointer;padding:13px 16px;font-size:13px;font-weight:600;background:#f5f7fb}.filepath{font-size:11px;color:var(--muted);padding:0 15px;overflow-wrap:anywhere}pre .explain{color:#9ee2c6;white-space:pre-wrap}pre{background:#14233a;color:#e1ebff;margin:0;padding:18px;overflow:auto;font-size:12px;line-height:1.7;tab-size:4}.ln{display:inline-block;width:35px;color:#8299bb;user-select:none}.closing{text-align:center;font-size:23px;margin:35px 0 10px}.toolbar{display:flex;gap:10px;flex-wrap:wrap;margin-top:25px}button,.toolbar a{border:1px solid #6a87b2;background:transparent;color:white;border-radius:8px;padding:9px 16px;font:inherit;font-size:13px;cursor:pointer;text-decoration:none}footer{text-align:center;font-size:12px;color:var(--muted);padding:0 25px 30px}body.presentation nav{display:none}body.presentation .layout{display:block;max-width:1300px}body.presentation section{min-height:80vh}a:focus-visible,button:focus-visible,summary:focus-visible{outline:3px solid #ffbc42;outline-offset:3px}@media(max-width:1000px){.layout{grid-template-columns:190px minmax(0,1fr)}main{padding:20px}section{padding:24px}.three{grid-template-columns:1fr}.two{grid-template-columns:1fr}}@media(max-width:700px){.layout{display:block}nav{position:relative;height:auto;display:flex;overflow:auto;padding:12px;gap:8px}nav a{flex:0 0 155px}main{padding:12px}section{padding:22px 16px}h2{font-size:25px}.flow{flex-direction:column}.flow .node{flex-basis:auto}.arrow{transform:rotate(90deg)}header{padding:36px 24px}.architecture{padding:12px}}@media print{@page{size:A4 landscape;margin:12mm}body{background:white;font-size:11px}header{background:white;color:#192843;padding:15px;break-after:page}header p{color:#5c6d85}nav,.toolbar,footer{display:none!important}.layout{display:block}main{padding:0}section{border:none;break-before:page;padding:8px;margin:0;min-height:0!important}h2{font-size:24px}.lead{font-size:14px}.node{padding:10px}details:not([open]){display:none}pre{white-space:pre-wrap;font-size:9px}aside{padding:10px}.three{grid-template-columns:repeat(3,1fr)}.two{grid-template-columns:repeat(2,1fr)}}`;
const html=`<!doctype html><html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SAI 배치 아키텍처 · 팀 설명자료</title><style>${style}</style></head><body><header><div class="eyebrow">SAI ENGINEERING / BATCH WALKTHROUGH</div><h1>매일의 거래와 기한을<br>10개의 배치로 관리하기</h1><p>Job·Step의 기본 개념부터 6개 업무 흐름, 코드 연결, 실패 처리까지.<br>팀원이 전체 구조를 먼저 이해하고 필요한 코드를 찾아갈 수 있는 설명자료입니다.</p><div class="toolbar"><button id="presentation" type="button" aria-pressed="false">발표 모드</button><button id="sources" type="button" aria-expanded="true">코드 모두 접기</button><button id="print" type="button">인쇄 / PDF 저장</button><a href="presenter-guide.html">발표자 상세 가이드 ↗</a></div><p style="font-size:12px">2026-10-27 로컬 코드 기준 · 10 Jobs / 6 Groups / 2 Chunk + 8 Tasklet<br>외부 라이브러리·폰트·네트워크 요청 없이 열리는 독립 HTML</p></header><div class="layout"><nav aria-label="자료 목차">${sections.map((s,i)=>`<a href="#${s.id}">${String(i+1).padStart(2,'0')} ${s.title}</a>`).join('')}</nav><main>${sections.map(s=>`<section id="${s.id}"><div class="kicker">${s.kicker}</div><h2>${s.title}</h2><p class="lead">${s.lead}</p>${s.body}</section>`).join('')}</main></div><footer>SAI · 소스 기반 교육 자료 / 선택 이유는 구조상 해석 / 업무 코드 변경 없음</footer><script>
document.getElementById('presentation').onclick=function(){const on=document.body.classList.toggle('presentation');this.setAttribute('aria-pressed',on);this.textContent=on?'일반 모드':'발표 모드'};
document.getElementById('sources').onclick=function(){const on=this.getAttribute('aria-expanded')!=='true';document.querySelectorAll('details.source').forEach(d=>d.open=on);this.setAttribute('aria-expanded',on);this.textContent=on?'코드 모두 접기':'코드 모두 펼치기'};
document.getElementById('print').onclick=()=>window.print();
const links=[...document.querySelectorAll('nav a')];const observer=new IntersectionObserver(es=>{for(const e of es)if(e.isIntersecting){links.forEach(a=>a.classList.toggle('active',a.hash==='#'+e.target.id));}},{rootMargin:'-10% 0px -65% 0px'});document.querySelectorAll('section').forEach(s=>observer.observe(s));
</script></body></html>`;
fs.writeFileSync(path.join(__dirname,'batch-explained.html'),html);
console.log(`Created presentation: ${sections.length} sections, ${jobs.length} jobs, ${html.length} characters`);
