package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationAgentDraft;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningViolationResponse;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.service.PreparationPlanningTools;
import org.teamsai.saibackend.domain.calendar.support.PreparationPlanningValidator;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparationPlanningToolsTest {

    private PreparationPlanningTools tools;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-08T00:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        RepaymentCandidate candidate = new RepaymentCandidate(
                100L,
                10L,
                "계약 A",
                LocalDate.of(2026, 10, 15),
                new BigDecimal("100000"),
                false
        );

        PreparationPlanningContext context =
                new PreparationPlanningContext(
                        LocalDate.of(2026, 10, 8),
                        YearMonth.of(2026, 10),
                        List.of(candidate),
                        List.of(),
                        List.of()
                );

        PreparationProposalRequest request =
                new PreparationProposalRequest(
                        YearMonth.of(2026, 10),
                        Set.of(DayOfWeek.TUESDAY),
                        LocalTime.of(18, 0),
                        LocalTime.of(21, 0),
                        1,
                        2,
                        "",
                        null
                );

        tools = new PreparationPlanningTools(
                context,
                request,
                new PreparationPlanningValidator(),
                clock
        );
    }

    @Test
    void acceptsSameFinalDraftAfterReadingAndChecking() {
        tools.readRepaymentFacts();
        tools.readFundingAndHistory();

        var draft = draft(10L, "납기 전에 확인하세요.");

        var validation = tools.validateReminderDraft(draft);

        assertThat(validation.valid()).isTrue();
        assertThat(tools.wasFinalDraftChecked(draft)).isTrue();
    }

    @Test
    void rejectsFinalDraftWhenFactsWereNotRead() {
        var draft = draft(10L, "납기 전에 확인하세요.");

        assertThrows(
                IllegalStateException.class,
                () -> tools.validateReminderDraft(draft)
        );

        assertThrows(
                IllegalStateException.class,
                () -> tools.getCheckedDraft()
        );
    }

    @Test
    void rejectsDraftChangedAfterToolValidation() {
        tools.readRepaymentFacts();
        tools.readFundingAndHistory();

        tools.validateReminderDraft(
                draft(10L, "납기 전에 확인하세요.")
        );

        assertThat(tools.wasFinalDraftChecked(
                draft(10L, "검증 후 변경된 이유")
        )).isFalse();
    }

    @Test
    void returnsViolationForUnknownSchedule() {
        tools.readRepaymentFacts();
        tools.readFundingAndHistory();

        var result = tools.validateReminderDraft(
                draft(999L, "알 수 없는 회차")
        );

        assertThat(result.valid()).isFalse();

        assertThat(result.violations())
                .extracting(PreparationPlanningViolationResponse::code)
                .contains("UNKNOWN_SCHEDULE");
    }

    @Test
    void limitsToolCalls() {
        for (int i = 0; i < 8; i++) {
            tools.readRepaymentFacts();
        }

        assertThrows(
                IllegalStateException.class,
                () -> tools.readRepaymentFacts()
        );
    }

    private PreparationAgentDraft draft(
            Long scheduleId,
            String reason
    ) {
        return new PreparationAgentDraft(
                List.of(
                        new PreparationAgentDraft.Slot(
                                scheduleId,
                                "2026-10-13T09:00:00Z",
                                reason
                        )
                )
        );
    }
}