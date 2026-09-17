package org.teamsai.saibackend.domain.batch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.teamsai.saibackend.domain.batch.common.notification.SlackNotifier;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.service.SettlementAbandonmentDetectionService;
import org.teamsai.saibackend.domain.settlement.service.SettlementAbandonmentResult;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementAbandonmentDetectionServiceTest {

    @Mock
    private OverdueCriteria overdueCriteria;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private SettlementAbandonmentAlertRepository abandonmentAlertRepository;
    @Mock
    private SlackNotifier slackNotifier;

    @InjectMocks
    private SettlementAbandonmentDetectionService service;

    private final LocalDate baseDate = LocalDate.of(2026, 8, 19);

    private Settlement settlement(Long id, String title) {
        Settlement settlement = mock(Settlement.class);
        lenient().when(settlement.getSettlementId()).thenReturn(id);
        lenient().when(settlement.getTitle()).thenReturn(title);
        return settlement;
    }

    @Nested
    class NoTargets {

        @Test
        void 대상_정산이_없으면_0건을_반환한다() {
            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(0L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(Collections.emptyList());

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isZero();
            verifyNoInteractions(abandonmentAlertRepository, slackNotifier);
        }
    }

    @Nested
    class AbandonmentJudgement {

        @Test
        void referenceDate가_null이면_방치로_판단하지_않는다() {
            Settlement s = settlement(1L, "정산A");
            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(1L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(s));
            when(overdueCriteria.resolveReferenceDate(s)).thenReturn(null);

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isZero();
            verifyNoInteractions(abandonmentAlertRepository, slackNotifier);
        }

        @Test
        void 기준일로부터_3일_경과전이면_방치로_판단하지_않는다() {
            Settlement s = settlement(1L, "정산A");
            LocalDate referenceDate = baseDate.minusDays(2);

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(1L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(s));
            when(overdueCriteria.resolveReferenceDate(s)).thenReturn(referenceDate);

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isZero();
            verify(abandonmentAlertRepository, never())
                    .existsBySettlementIdAndReferenceDate(any(), any());
            verifyNoInteractions(slackNotifier);
        }

        @Test
        void 정확히_3일_경과한_경계값은_방치로_판단한다() {
            Settlement s = settlement(1L, "정산A");
            LocalDate referenceDate = baseDate.minusDays(3);

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(1L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(s));
            when(overdueCriteria.resolveReferenceDate(s)).thenReturn(referenceDate);
            when(abandonmentAlertRepository.existsBySettlementIdAndReferenceDate(
                    1L,
                    referenceDate
            )).thenReturn(false);

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isEqualTo(1);
            verify(abandonmentAlertRepository)
                    .existsBySettlementIdAndReferenceDate(1L, referenceDate);
            verify(abandonmentAlertRepository)
                    .saveAndFlush(any(SettlementAbandonmentAlert.class));
            verify(slackNotifier).send(any());
        }

        @Test
        void 이미_알림이_존재하면_재알림하지_않는다() {
            Settlement s = settlement(1L, "정산A");
            LocalDate referenceDate = baseDate.minusDays(5);

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(1L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(s));
            when(overdueCriteria.resolveReferenceDate(s)).thenReturn(referenceDate);
            when(abandonmentAlertRepository.existsBySettlementIdAndReferenceDate(
                    1L,
                    referenceDate
            )).thenReturn(true);

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isZero();
            verify(abandonmentAlertRepository)
                    .existsBySettlementIdAndReferenceDate(1L, referenceDate);
            verify(abandonmentAlertRepository, never()).saveAndFlush(any());
            verifyNoInteractions(slackNotifier);
        }
    }

    @Nested
    class Pagination {

        @Test
        void 페이지가_가득차면_다음_페이지를_계속_조회한다() {
            List<Settlement> fullPage = Collections.nCopies(
                    100,
                    settlement(1L, "정산")
            );

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(150L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(fullPage);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(1, 100)
            )).thenReturn(Collections.emptyList());
            when(overdueCriteria.resolveReferenceDate(any())).thenReturn(null);

            service.detectAbandoned(baseDate);

            verify(settlementRepository)
                    .findBySettlementStatusOrderBySettlementIdAsc(
                            SettlementStatus.IN_PROGRESS,
                            PageRequest.of(0, 100)
                    );
            verify(settlementRepository)
                    .findBySettlementStatusOrderBySettlementIdAsc(
                            SettlementStatus.IN_PROGRESS,
                            PageRequest.of(1, 100)
                    );
        }

        @Test
        void 마지막_페이지가_PAGE_SIZE보다_작으면_추가조회하지_않는다() {
            Settlement s = settlement(1L, "정산A");

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(1L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(s));
            when(overdueCriteria.resolveReferenceDate(s)).thenReturn(null);

            service.detectAbandoned(baseDate);

            verify(settlementRepository, times(1))
                    .findBySettlementStatusOrderBySettlementIdAsc(
                            eq(SettlementStatus.IN_PROGRESS),
                            any(PageRequest.class)
                    );
        }
    }

    @Nested
    class MultipleSettlementsInOnePage {

        @Test
        void 여러건_중_방치건만_선별해서_카운트한다() {
            Settlement abandoned = settlement(1L, "방치됨");
            Settlement notAbandoned = settlement(2L, "정상");
            Settlement nullRef = settlement(3L, "기준일없음");

            when(settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenReturn(3L);
            when(settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS,
                    PageRequest.of(0, 100)
            )).thenReturn(List.of(abandoned, notAbandoned, nullRef));

            when(overdueCriteria.resolveReferenceDate(abandoned))
                    .thenReturn(baseDate.minusDays(10));
            when(overdueCriteria.resolveReferenceDate(notAbandoned))
                    .thenReturn(baseDate.minusDays(1));
            when(overdueCriteria.resolveReferenceDate(nullRef))
                    .thenReturn(null);

            when(abandonmentAlertRepository.existsBySettlementIdAndReferenceDate(
                    eq(1L),
                    any()
            )).thenReturn(false);

            SettlementAbandonmentResult result = service.detectAbandoned(baseDate);

            assertThat(result.detectedCount()).isEqualTo(1);

            verify(abandonmentAlertRepository)
                    .existsBySettlementIdAndReferenceDate(eq(1L), any());

            verify(abandonmentAlertRepository, never())
                    .existsBySettlementIdAndReferenceDate(eq(2L), any());

            verify(abandonmentAlertRepository, never())
                    .existsBySettlementIdAndReferenceDate(eq(3L), any());

            verify(abandonmentAlertRepository, times(1))
                    .saveAndFlush(any(SettlementAbandonmentAlert.class));

            verify(slackNotifier, times(1)).send(any());
        }
    }
}