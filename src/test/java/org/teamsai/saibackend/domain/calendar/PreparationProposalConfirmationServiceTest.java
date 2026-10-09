package org.teamsai.saibackend.domain.calendar;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.entity.*;
import org.teamsai.saibackend.domain.calendar.repository.*;
import org.teamsai.saibackend.domain.calendar.service.*;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparationProposalConfirmationServiceTest {

    private PreparationProposalRepository proposals;
    private RepaymentPreparationEventRepository events;
    private RepaymentScheduleRepository schedules;
    private PreparationPlanningContextService contexts;
    private EntityManager entityManager;

    private PreparationProposalConfirmationService service;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-08T00:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    private static final String ID =
            "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        proposals = mock(PreparationProposalRepository.class);
        events = mock(RepaymentPreparationEventRepository.class);
        schedules = mock(RepaymentScheduleRepository.class);
        contexts = mock(PreparationPlanningContextService.class);
        entityManager = mock(EntityManager.class);

        service = new PreparationProposalConfirmationService(
                proposals,
                events,
                schedules,
                contexts,
                new PreparationPlanningValidator(),
                entityManager,
                clock
        );
    }

    @Test
    void confirmsAndReusesResultWithoutCreatingAnotherEvent() {
        stubValidProposal(clock.instant().plusSeconds(900));

        when(events.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> {
                    List<RepaymentPreparationEvent> rows =
                            invocation.getArgument(0);

                    long id = 1L;
                    for (RepaymentPreparationEvent row : rows) {
                        ReflectionTestUtils.setField(
                                row,
                                "eventId",
                                id++
                        );
                    }

                    return rows;
                });

        var first = service.confirm(1L, ID);
        var second = service.confirm(1L, ID);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.events()).isEqualTo(first.events());
        assertThat(second.confirmedAt())
                .isEqualTo(first.confirmedAt());

        verify(events, times(1)).saveAllAndFlush(anyList());
        verify(contexts, times(1))
                .load(1L, YearMonth.of(2026, 10));
    }

    @Test
    void rejectsExpiredProposalWithoutSavingEvents() {
        stubValidProposal(clock.instant().minusSeconds(1));

        assertThatThrownBy(() -> service.confirm(1L, ID))
                .isInstanceOf(DomainException.class)
                .hasMessage("제안이 만료되었습니다. 새 제안을 생성하세요.");

        verify(events, never()).saveAllAndFlush(anyList());
        verifyNoInteractions(contexts);
    }

    @Test
    void rejectsChangedRemainingAmount() {
        stubValidProposal(clock.instant().plusSeconds(900));

        when(contexts.load(1L, YearMonth.of(2026, 10)))
                .thenReturn(context(new BigDecimal("90000")));

        assertThatThrownBy(() -> service.confirm(1L, ID))
                .isInstanceOf(DomainException.class)
                .hasMessage(
                        "상환 상태 또는 준비 일정이 변경되었습니다. 새 제안을 생성하세요."
                );

        verify(events, never()).saveAllAndFlush(anyList());
    }

    @Test
    void rejectsOtherUsersProposal() {
        when(proposals.findOwnedForUpdate(ID, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(2L, ID))
                .isInstanceOf(DomainException.class)
                .hasMessage("승인할 제안을 찾을 수 없습니다.");

        verify(events, never()).saveAllAndFlush(anyList());
    }

    private void stubValidProposal(Instant expiresAt) {
        PreparationProposalRequest request =
                new PreparationProposalRequest(
                        YearMonth.of(2026, 10),
                        Set.of(DayOfWeek.TUESDAY),
                        LocalTime.of(18, 0),
                        LocalTime.of(21, 0),
                        30,
                        2,
                        "",
                        null
                );

        PreparationProposalItem item =
                new PreparationProposalItem(
                        100L,
                        10L,
                        "계약 A",
                        new BigDecimal("120000"),
                        LocalDate.of(2026, 10, 15),
                        false,
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-13T09:30:00Z"),
                        "납기 전에 준비합니다."
                );

        PreparationProposal proposal =
                new PreparationProposal(
                        ID,
                        1L,
                        mapper.writeValueAsString(
                                new StoredPreparationProposal(
                                        request,
                                        List.of(item)
                                )
                        ),
                        clock.instant(),
                        expiresAt
                );

        when(proposals.findOwnedForUpdate(ID, 1L))
                .thenReturn(Optional.of(proposal));

        User user = mock(User.class);

        when(entityManager.find(
                User.class,
                1L,
                LockModeType.PESSIMISTIC_WRITE
        )).thenReturn(user);

        RepaymentSchedule schedule =
                mock(RepaymentSchedule.class);

        when(schedule.getStatus())
                .thenReturn(RepaymentScheduleStatus.PENDING);

        when(schedules.findByIdForUpdate(10L))
                .thenReturn(Optional.of(schedule));

        when(contexts.load(1L, YearMonth.of(2026, 10)))
                .thenReturn(context(new BigDecimal("120000")));
    }

    private PreparationPlanningContext context(
            BigDecimal remaining
    ) {
        return new PreparationPlanningContext(
                LocalDate.of(2026, 10, 8),
                YearMonth.of(2026, 10),
                List.of(
                        new RepaymentCandidate(
                                100L,
                                10L,
                                "계약 A",
                                LocalDate.of(2026, 10, 15),
                                remaining,
                                false
                        )
                ),
                List.of(),
                List.of()
        );
    }
}