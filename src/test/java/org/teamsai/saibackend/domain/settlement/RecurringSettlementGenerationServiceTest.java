package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.RecurringSettlementRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.*;
import org.teamsai.saibackend.domain.settlement.type.CycleRule;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringSettlementGenerationServiceTest {

    @Mock
    private RecurringSettlementRepository recurringSettlementRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private RecurringSettlementCycleGenerator cycleGenerator;

    @InjectMocks
    private RecurringSettlementGenerationService sut;


    private RecurringSettlement recurring(
            Long id,
            LocalDate startDate
    ) {
        return recurring(id, startDate, null);
    }

    private RecurringSettlement recurring(
            Long id,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return RecurringSettlement.builder()
                .recurringSettlementId(id)
                .cycleRule(CycleRule.MONTHLY)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    private Settlement settlement(
            Long id,
            Long recurringId,
            LocalDate cycleDate
    ) {
        RecurringSettlement recurring =
                RecurringSettlement.builder()
                        .recurringSettlementId(recurringId)
                        .build();

        return Settlement.builder()
                .settlementId(id)
                .recurringSettlement(recurring)
                .cycleDate(cycleDate)
                .build();
    }


    @Test
    @DisplayName("직전 회차가 없으면 캐치업을 시도하지 않는다")
    void skipsWhenNoLatestSettlement() {

        LocalDate baseDate = LocalDate.of(2026, 3, 1);
        RecurringSettlement r1 =
                recurring(1L, LocalDate.of(2026, 1, 31));

        when(
                recurringSettlementRepository.findActiveInRange(baseDate)
        ).thenReturn(
                List.of(r1)
        );

        when(
                settlementRepository.findLatestByRecurringId(
                        eq(1L),
                        any(Pageable.class)
                )
        ).thenReturn(
                List.of()
        );

        RecurringSettlementBatchResult result =
                sut.generateTodaySettlements(baseDate);

        verify(cycleGenerator, never())
                .generateOneCycle(any(), any(), any());

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
    }


    @Test
    @DisplayName("도래한 회차가 하나면 정확히 1번만 생성을 시도한다")
    void generatesExactlyOneCycleWhenOnlyOneIsDue() {

        LocalDate baseDate = LocalDate.of(2026, 2, 28);

        RecurringSettlement r1 =
                recurring(1L, LocalDate.of(2026, 1, 31));

        Settlement latest =
                settlement(
                        10L,
                        1L,
                        LocalDate.of(2026, 1, 31)
                );

        Settlement created =
                settlement(
                        11L,
                        1L,
                        LocalDate.of(2026, 2, 28)
                );

        when(
                recurringSettlementRepository.findActiveInRange(baseDate)
        ).thenReturn(
                List.of(r1)
        );

        when(
                settlementRepository.findLatestByRecurringId(
                        eq(1L),
                        any(Pageable.class)
                )
        ).thenReturn(
                List.of(latest)
        );

        when(
                settlementRepository.countByRecurringId(1L)
        ).thenReturn(
                1L
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        1
                )
        ).thenReturn(
                LocalDate.of(2026, 2, 28)
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        2
                )
        ).thenReturn(
                LocalDate.of(2026, 3, 31)
        );

        when(
                cycleGenerator.generateOneCycle(
                        r1,
                        latest,
                        LocalDate.of(2026, 2, 28)
                )
        ).thenReturn(
                CycleGenerationOutcome.created(created)
        );

        RecurringSettlementBatchResult result =
                sut.generateTodaySettlements(baseDate);

        verify(cycleGenerator, times(1))
                .generateOneCycle(any(), any(), any());

        assertThat(result.succeeded()).isEqualTo(1);
    }


    @Test
    @DisplayName("배치가 밀려 여러 회차가 도래했으면 baseDate까지 연속으로 캐치업한다")
    void catchesUpMultipleOverdueCycles() {

        LocalDate baseDate = LocalDate.of(2026, 4, 15);

        RecurringSettlement r1 =
                recurring(1L, LocalDate.of(2026, 1, 31));

        Settlement cycle1 =
                settlement(10L, 1L, LocalDate.of(2026, 1, 31));

        Settlement cycle2 =
                settlement(11L, 1L, LocalDate.of(2026, 2, 28));

        Settlement cycle3 =
                settlement(12L, 1L, LocalDate.of(2026, 3, 31));

        when(
                recurringSettlementRepository.findActiveInRange(baseDate)
        ).thenReturn(
                List.of(r1)
        );

        when(
                settlementRepository.findLatestByRecurringId(
                        eq(1L),
                        any(Pageable.class)
                )
        ).thenReturn(
                List.of(cycle1)
        );

        when(
                settlementRepository.countByRecurringId(1L)
        ).thenReturn(
                1L
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        1
                )
        ).thenReturn(
                LocalDate.of(2026, 2, 28)
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        2
                )
        ).thenReturn(
                LocalDate.of(2026, 3, 31)
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        3
                )
        ).thenReturn(
                LocalDate.of(2026, 4, 30)
        );

        when(
                cycleGenerator.generateOneCycle(
                        r1,
                        cycle1,
                        LocalDate.of(2026, 2, 28)
                )
        ).thenReturn(
                CycleGenerationOutcome.created(cycle2)
        );

        when(
                cycleGenerator.generateOneCycle(
                        r1,
                        cycle2,
                        LocalDate.of(2026, 3, 31)
                )
        ).thenReturn(
                CycleGenerationOutcome.created(cycle3)
        );

        RecurringSettlementBatchResult result =
                sut.generateTodaySettlements(baseDate);

        verify(cycleGenerator)
                .generateOneCycle(
                        r1,
                        cycle1,
                        LocalDate.of(2026, 2, 28)
                );

        verify(cycleGenerator)
                .generateOneCycle(
                        r1,
                        cycle2,
                        LocalDate.of(2026, 3, 31)
                );

        verify(cycleGenerator, times(2))
                .generateOneCycle(any(), any(), any());

        assertThat(result.succeeded()).isEqualTo(1);
    }


    @Test
    @DisplayName("캐치업 도중 한 회차가 실패하면 이후 회차를 중단하고 실패로 집계한다")
    void stopsCatchUpOnFailureAndCountsAsFailed() {

        LocalDate baseDate = LocalDate.of(2026, 4, 15);

        RecurringSettlement r1 =
                recurring(1L, LocalDate.of(2026, 1, 31));

        Settlement cycle1 =
                settlement(10L, 1L, LocalDate.of(2026, 1, 31));

        Settlement cycle2 =
                settlement(11L, 1L, LocalDate.of(2026, 2, 28));

        when(
                recurringSettlementRepository.findActiveInRange(baseDate)
        ).thenReturn(
                List.of(r1)
        );

        when(
                settlementRepository.findLatestByRecurringId(
                        eq(1L),
                        any(Pageable.class)
                )
        ).thenReturn(
                List.of(cycle1)
        );

        when(
                settlementRepository.countByRecurringId(1L)
        ).thenReturn(
                1L
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        1
                )
        ).thenReturn(
                LocalDate.of(2026, 2, 28)
        );

        when(
                cycleGenerator.calculateNthCycleDate(
                        r1.getStartDate(),
                        CycleRule.MONTHLY,
                        2
                )
        ).thenReturn(
                LocalDate.of(2026, 3, 31)
        );

        when(
                cycleGenerator.generateOneCycle(
                        r1,
                        cycle1,
                        LocalDate.of(2026, 2, 28)
                )
        ).thenReturn(
                CycleGenerationOutcome.created(cycle2)
        );

        when(
                cycleGenerator.generateOneCycle(
                        r1,
                        cycle2,
                        LocalDate.of(2026, 3, 31)
                )
        ).thenThrow(
                new IllegalStateException("insert 실패")
        );

        RecurringSettlementBatchResult result =
                sut.generateTodaySettlements(baseDate);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedRecurringIds())
                .containsExactly(1L);
    }
}