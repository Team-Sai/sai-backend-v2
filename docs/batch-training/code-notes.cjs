// 실제 Java 원문을 유지하면서 교육자료에 삽입할 설명 주석.
module.exports={
 'DailyBatchPipelineScheduler.java':[
 ['@Scheduled','한국시간 자정에 스케줄 진입점을 호출한다.'],['@SchedulerLock','여러 인스턴스 중 같은 이름의 스케줄 락을 획득한 실행이 진입한다.'],['LocalDate baseDate','이번 실행의 업무 기준일을 JVM 기본 시간대로 계산한다.'],['runStep("','이 Job을 실행 요청한다. 다음 줄의 Job도 별개의 실행 이력을 가진다.'],['.addString("baseDate"','기한 판단에 사용할 날짜를 JobParameters로 전달한다.'],['.addLong("runId"','호출 시각을 식별 파라미터에 넣어 실행을 구별한다.'],['jobOperator.start','JobConfig에 정의한 Job을 실행한다.'],['catch (Exception','실행 요청의 예외를 기록한 뒤 메서드를 끝내 다음 Job 호출로 진행한다.']],
 'LinkedAccountSyncTargetReaderFactory.java':[
 ['public JpaPagingItemReader','두 거래 Job이 재사용하는 계좌 Reader를 만든다.'],['.queryString','조회 항목은 연결 계좌 ID와 사용자 ID이며 DTO로 반환한다.'],['.parameterValues','위 쿼리의 status 파라미터에 AVAILABLE을 전달한다.'],['.pageSize','DB 조회 페이지 크기는 PAGE_SIZE=50이다.']],
 'TransactionSyncJobConfig.java':[
 ['new JobBuilder','Job 이름과 실행 이력 저장소를 연결한다.'],['.start(transactionSyncStep)','이 Job의 시작 단계로 거래 동기화 Step 하나를 연결한다.'],['.listener(logging','Job 시작·종료 공통 로그 및 Slack 결과 전송을 연결한다.'],['chunk(50)','계좌 DTO 최대 50개를 한 chunk 처리 경계로 묶는다.'],['.reader(','AVAILABLE 계좌 DTO를 공급하는 Reader를 연결한다.'],['.processor(','계좌마다 동기화·매칭 서비스를 실행하는 Processor를 연결한다.'],['.writer(','처리된 계좌 묶음을 로그로 남기는 Writer를 연결한다.'],['.retry(','이 타입의 일시적 은행 조회 예외를 재시도 대상으로 지정한다.'],['.retryLimit','이 Step에 설정된 재시도 한도 값은 2다.'],['.skipLimit','허용 스킵 한도를 넘으면 Step이 실패한다.'],['syncAndMatch(','계좌의 거래를 동기화한 다음 자동 매칭한다. 실제 업무 저장도 서비스에서 수행한다.'],['return target','처리한 동일 계좌 DTO를 Writer로 전달한다.'],['private ItemWriter','Writer는 처리 결과 묶음을 받으며 이 구현은 완료 로그를 출력한다.'],['return chunk -> log.info','chunk.size()는 이 Writer가 받은 계좌 수다.']],
 'BankTransactionRetryService.java':[
 ['findRetryCandidates','저장된 거래 중 재시도 정책에 맞는 후보를 조회한다.'],['deleteAllByBankTransactionId','해당 거래의 이전 매칭 후보를 제거한다.'],['resetToPendingForRetry','기존 처리 상태를 확인하며 다시 매칭할 PENDING 상태로 전환한다.'],['bankMatchingService.execute','초기화된 거래에 대해 자동 매칭을 다시 수행한다.'],['checkAndNotifyExhausted','재시도 후에도 해결되지 않고 한도에 도달한 거래를 운영자에게 알린다.']],
 'RecurringSettlementGenerationService.java':[
 ['calculateNthCycleDate','시작일·반복 규칙·누적 회차 수로 다음 날짜를 계산한다.'],['baseDate.isBefore','다음 날짜가 기준일보다 미래이면 생성을 종료한다.'],['recurring.getEndDate() != null','반복 설정의 종료일을 초과하는 회차는 생성을 종료한다.'],['generatedThisRun >=','설정 하나당 이번 실행의 생성 상한은 31회차다.'],['cycleGenerator.generateOneCycle','회차 하나를 독립 트랜잭션으로 생성한다.'],['case CREATED','새 회차를 다음 반복의 기준으로 삼고 회차 수를 증가시킨다.'],['case CONCURRENTLY_SKIPPED','다른 실행의 회차 생성이 확인되면 이번 설정 처리를 종료한다.'],['catch (Exception','해당 설정의 실패를 반환하며 바깥 반복은 다른 설정을 계속 처리한다.']],
 'RecurringSettlementCycleService.java':[
 ['@Transactional','회차 하나의 생성 결과를 독립 트랜잭션으로 커밋·롤백한다.'],['Settlement lockedLatest','최신 회차를 잠금 조회해 동시 생성 여부를 확인한다.'],['if (lockedLatest','현재 최신 회차와 호출 시점에 알던 직전 회차를 비교한다.'],['List<SettlementParticipant> activeParticipants','기존 회차에서 ACTIVE 참여자만 승계 대상으로 조회한다.']],
 'RepaymentScheduleService.java':[
 ['findScheduleIdsByStatusAndDueDateLessThanEqual','OVERDUE이면서 납부일이 cutoffDate 이하인 스케줄 ID를 찾는다.'],['repaymentScheduleRepository.updateStatusBulk','현재 상태가 OVERDUE인 ID만 WRITTEN_OFF로 벌크 갱신한다.'],['repaymentScheduleRepository.markOverdueBulk','PENDING이면서 dueDate가 baseDate 이전인 스케줄을 OVERDUE로 갱신한다.']],
 'OverdueSettlementUpdateService.java':[
 ['@Transactional','정산 하나의 연체 갱신을 독립 트랜잭션에서 실행한다.'],['List<Long> activeParticipantIds','현재 ACTIVE 참여자의 ID를 조회한다.'],['LocalDateTime overdueSince','연체 시작일을 정산 기준일 다음 날 00:00으로 정한다.'],['markOverdueByParticipantIds','활성 미납·부분납부 의무 중 overdueSince가 없는 대상에 기록한다.']],
 'ReminderNotificationSender.java':[
 ['@Transactional','상환 알림 하나의 저장을 독립 트랜잭션으로 처리한다.'],['findDebtorUserId','계약에 연결된 채무자 사용자를 찾는다.'],['if (debtorUserId','수신자를 찾지 못한 스케줄은 SKIPPED 결과로 반환한다.'],['notificationService.createIfAbsent','알림 종류와 참조 ID 등을 사용해 중복을 확인하고 저장한다.'],['return Outcome.PROCESSED','알림 서비스 호출이 정상 종료된 스케줄로 집계한다.']],
 'SettlementReminderSender.java':[
 ['notificationService.createIfAbsentInNewTransaction','결제의무별 알림을 독립 트랜잭션에서 중복 확인 후 저장한다.'],['obligation.paymentObligationId()','알림 중복 확인의 참조 대상은 결제의무 ID다.'],['settlement.getSettlementId()','정산 ID를 추가 참조 정보로 저장한다.']],
 'SettlementAbandonmentNotifier.java':[
 ['@Transactional','방치 알림 한 건의 전달 상태 변경을 독립 트랜잭션에서 수행한다.'],['var alert =','전달 기록을 잠금 조회해 경쟁 실행을 직렬화한다.'],['if (alert ==','PENDING 기록에 대해서만 전송을 진행한다.'],['if (!slackNotifier.trySend','전송 실패 시 false로 끝나며 DB 기록은 PENDING으로 남는다.'],['alert.markSent','Slack 전송 성공 후 SENT와 전송 시각을 저장한다.']],
 'SettlementWriteOffBatchService.java':[
 ['LocalDateTime cutoff','연체 시작일 기준 30일 경과 여부를 비교할 경계 시각이다.'],['findWriteOffCandidateIds','활성·미납/부분납부·연체 시작일 조건에 맞는 후보를 찾는다.'],['writeOffTransactionExecutor.writeOffOneBatch','잠금 후 현재 상태를 재검사하고 실제 상각 변경을 독립 커밋한다.'],['findSettlementIdsByObligationIds','후보 의무가 속한 정산 ID를 조회한다.'],['autoCloseIfAllResolved','정산의 모든 의무가 해결됐으면 CLOSED로 바꾼다.'],['catch (Exception','자동종결 실패를 기록하고 다음 정산 검사를 계속한다.']],
 'RepaymentWriteOffTransactionExecutor.java':[
 ['@Transactional','전체 스케줄 상각 반복을 하나의 독립 트랜잭션으로 실행한다.'],['for (List<Long> chunk','ID를 500개씩 나눠 UPDATE하며 커밋은 이 메서드 경계에서 수행한다.'],['total +=','현재 OVERDUE인 스케줄만 상각하고 실제 UPDATE 건수를 합산한다.']],
 'LoggingJobExecutionListener.java':[
 ['public void beforeJob','Job 시작 시 실행 이름과 파라미터를 기록한다.'],['public void afterJob','Job 종료 후 최종 상태와 Step 집계를 읽는다.'],['status == BatchStatus.COMPLETED','프레임워크의 최종 상태를 기준으로 로그의 성공·실패를 표시한다.'],['slackNotifier.send','실행 결과·소요시간·read/write/skip 집계를 Slack에 전달한다.']],
 'RecurringSettlementGenerationJobConfig.java':[
 ['new StepBuilder','정기정산 생성 Step을 실행 이력 저장소에 연결한다.'],['.tasklet(','업무 절차를 실행할 Tasklet과 Step 트랜잭션 관리자를 연결한다.'],['LocalDate.parse','JobParameters의 문자열 기준일을 날짜로 바꾼다.'],['generateTodaySettlements','설정 조회와 누락 회차 생성 반복을 업무 서비스에 위임한다.'],['incrementWriteCount','성공 처리한 정기설정 수를 Step 집계에 반영한다.'],['incrementWriteSkipCount','실패한 정기설정 수를 Step 스킵 집계에 반영한다.'],['return RepeatStatus.FINISHED','이 Tasklet 호출을 끝내 Step을 종료한다.']]
};
