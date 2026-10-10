package org.teamsai.saibackend.domain.calendar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.teamsai.saibackend.domain.calendar.dto.internal.*;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.support.PreparationPlanningValidator;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

@Slf4j
public class PreparationPlanningTools {

    private static final int MAX_TOOL_CALLS = 8;

    private final PreparationPlanningContext context;
    private final PreparationProposalRequest request;
    private final PreparationPlanningValidator validator;
    private final Clock clock;

    private int toolCalls;
    private boolean factsRead;
    private boolean fundingAndHistoryRead;
    private PreparationAgentDraft lastCheckedDraft;

    public PreparationPlanningTools(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            PreparationPlanningValidator validator,
            Clock clock
    ) {
        this.context = Objects.requireNonNull(context);
        this.request = Objects.requireNonNull(request);
        this.validator = Objects.requireNonNull(validator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Tool(description = """
            로그인 사용자에 대해 서버가 조회한 상환 사실을 확인한다.
            candidates는 신규 확인 알림의 대상이다.
            allCandidates는 이미 알림이 등록된 회차까지 포함하는
            전체 관리 대상 미상환 회차다.
            금액과 납기는 서버가 계산한 사실이다.
            """)
    public synchronized RepaymentFacts readRepaymentFacts() {
        recordCall("READ_REPAYMENT_FACTS");
        factsRead = true;

        return new RepaymentFacts(
                context.analysisDate(),
                context.targetMonth(),
                context.candidates(),
                context.allCandidates(),
                context.existingEvents(),
                context.alreadyPlannedScheduleIds()
        );
    }

    @Tool(description = """
            서버가 계산한 예산 평가와 확정 상환 기록 집계를 확인한다.
            fundingAssessment가 null이면 예산 입력이 없는 것이다.
            repaymentHistory는 실제 송금 시간의 분석이 아니라
            원장에 확정 기록된 상환 집계다.
            """)
    public synchronized FundingAndHistory readFundingAndHistory() {
        recordCall("READ_FUNDING_AND_HISTORY");
        fundingAndHistoryRead = true;

        return new FundingAndHistory(
                context.fundingAssessment(),
                context.repaymentHistory()
        );
    }

    @Tool(
            returnDirect = true,
            description = """
                상환 확인 알림 후보 전체를 서버 규칙으로 검증한다.
                먼저 readRepaymentFacts와 readFundingAndHistory를
                각각 호출한 뒤 이 도구를 호출한다.
                draft는 slots 목록이며 각 항목은
                scheduleId, startsAt, reason을 포함한다.
                startsAt은 UTC ISO-8601 형식이다.
                이 도구를 호출하면 현재 생성 작업이 종료된다.
                검증 실패 시 재생성 여부는 서버가 결정한다.
                이 도구는 일정을 저장하거나 상환을 실행하지 않는다.
                """
    )
    public synchronized PreparationValidationResult validateReminderDraft(
            PreparationAgentDraft draft
    ) {
        recordCall("VALIDATE_REMINDER_DRAFT");

        // 검증 전에 필수 자료를 조회했는지 확인한다.
        if (!factsRead || !fundingAndHistoryRead) {
            throw new IllegalStateException(
                    "Required preparation facts were not read before validation"
            );
        }

        // 이전 후보가 남아 있지 않도록 먼저 비운다.
        lastCheckedDraft = null;

        PreparationValidationResult result =
                validator.validate(
                        context,
                        request,
                        draft,
                        clock.instant()
                );

        // 모델에 전달했던 목록과 분리해 검증 대상의 복사본을 보관한다.
        // null 항목을 포함하는 잘못된 후보도 외부 검증에서 처리할 수 있다.
        if (draft != null && draft.slots() != null) {
            lastCheckedDraft = new PreparationAgentDraft(
                    java.util.Collections.unmodifiableList(
                            new java.util.ArrayList<>(draft.slots())
                    )
            );
        }

        log.info(
                "event=PREPARATION_TOOL_VALIDATED valid={} violationCount={}",
                result.valid(),
                result.violations().size()
        );

        return result;
    }

    public synchronized PreparationAgentDraft getCheckedDraft() {
        if (toolCalls > MAX_TOOL_CALLS
                || !factsRead
                || !fundingAndHistoryRead
                || lastCheckedDraft == null) {
            throw new IllegalStateException(
                    "No checked preparation draft is available"
            );
        }

        return lastCheckedDraft;
    }

    public synchronized boolean wasFinalDraftChecked(
            PreparationAgentDraft finalDraft
    ) {
        return toolCalls <= MAX_TOOL_CALLS
                && factsRead
                && fundingAndHistoryRead
                && finalDraft != null
                && lastCheckedDraft != null
                && Objects.equals(lastCheckedDraft, finalDraft);
    }

    private void recordCall(String toolName) {
        toolCalls++;

        if (toolCalls > MAX_TOOL_CALLS) {
            throw new IllegalStateException(
                    "Preparation tool call limit exceeded"
            );
        }

        log.info(
                "event=PREPARATION_TOOL_CALL tool={} callCount={}",
                toolName,
                toolCalls
        );
    }

    public record RepaymentFacts(
            LocalDate analysisDate,
            YearMonth targetMonth,
            List<RepaymentCandidate> candidates,
            List<RepaymentCandidate> allCandidates,
            List<PreparationEventResponse> existingEvents,
            List<Long> alreadyPlannedScheduleIds
    ) {
    }

    public record FundingAndHistory(
            PreparationFundingAssessmentResponse fundingAssessment,
            PreparationRepaymentHistory repaymentHistory
    ) {
    }
}