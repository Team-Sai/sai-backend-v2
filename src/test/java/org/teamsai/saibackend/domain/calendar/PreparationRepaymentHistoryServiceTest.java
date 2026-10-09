package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.service.PreparationRepaymentHistoryService;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.repository.PreparationRepaymentHistoryProjection;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PreparationRepaymentHistoryServiceTest {

    private final PaymentRecordRepository repository =
            mock(PaymentRecordRepository.class);

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-10T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    private final PreparationRepaymentHistoryService service =
            new PreparationRepaymentHistoryService(repository, clock);

    @Test
    void readsThreeMonthSummaryForAuthenticatedUser() {
        var summary =
                mock(PreparationRepaymentHistoryProjection.class);

        when(summary.getConfirmedRecordCount()).thenReturn(3L);
        when(summary.getConfirmedRecordedAmount())
                .thenReturn(new BigDecimal("180000"));
        when(summary.getRecordedScheduleCount()).thenReturn(2L);

        LocalDateTime from =
                LocalDateTime.of(2026, 7, 10, 12, 0);

        LocalDateTime to =
                LocalDateTime.of(2026, 10, 10, 12, 0);

        when(repository.summarizePreparationHistory(1L, from, to))
                .thenReturn(summary);

        var result = service.read(1L);

        assertThat(result.confirmedRecordCount()).isEqualTo(3L);
        assertThat(result.confirmedRecordedAmount())
                .isEqualByComparingTo("180000");
        assertThat(result.recordedScheduleCount()).isEqualTo(2L);

        verify(repository)
                .summarizePreparationHistory(1L, from, to);
    }

    @Test
    void returnsZeroWhenNoSummaryIsAvailable() {
        var result = service.read(1L);

        assertThat(result.confirmedRecordCount()).isZero();
        assertThat(result.confirmedRecordedAmount()).isZero();
        assertThat(result.recordedScheduleCount()).isZero();
    }

    @Test
    void rejectsUnauthenticatedRequestBeforeQuerying() {
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.read(null)
        );

        verifyNoInteractions(repository);
    }
}