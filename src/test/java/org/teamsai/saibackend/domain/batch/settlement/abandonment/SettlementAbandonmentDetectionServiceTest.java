package org.teamsai.saibackend.domain.batch.settlement.abandonment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentRecorder;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementAbandonmentDetectionServiceTest {
    @Mock OverdueCriteria criteria;
    @Mock SettlementRepository settlements;
    @Mock SettlementAbandonmentRecorder recorder;
    @Mock SettlementAbandonmentNotifier notifier;
    @InjectMocks SettlementAbandonmentDetectionService service;
    private final LocalDate today = LocalDate.of(2026, 9, 27);

    @Test
    void noCandidatesStillDeliversPreviouslyPendingAlert() {
        var alert = SettlementAbandonmentAlert.pending(1L, today.minusDays(5), "original message");
        when(recorder.pendingAfter(null, null)).thenReturn(List.of(alert));

        assertThat(service.detectAbandoned(today).detectedCount()).isZero();

        verify(notifier).sendPending(1L, today.minusDays(5));
        verify(recorder, never()).recordIfAbsent(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3, 10})
    void recordsOnlySettlementsAtLeastThreeDaysPastDue(int days) {
        var settlement = candidate(today.minusDays(days));
        if (days >= 3) {
            when(recorder.recordIfAbsent(eq(1L), eq(today.minusDays(days)), anyString())).thenReturn(true);
        }

        assertThat(service.detectAbandoned(today).detectedCount()).isEqualTo(days >= 3 ? 1 : 0);

        if (days >= 3) {
            verify(recorder).recordIfAbsent(eq(1L), eq(today.minusDays(days)),
                    contains("감지일=2026-09-27"));
        } else {
            verify(recorder, never()).recordIfAbsent(any(), any(), any());
        }
    }

    @Test
    void nullReferenceDateIsSkipped() {
        candidate(null);
        assertThat(service.detectAbandoned(today).detectedCount()).isZero();
        verify(recorder, never()).recordIfAbsent(any(), any(), any());
    }

    @Test
    void existingRecordIsNotCountedAgain() {
        candidate(today.minusDays(3));
        when(recorder.recordIfAbsent(eq(1L), any(), any())).thenReturn(false);
        assertThat(service.detectAbandoned(today).detectedCount()).isZero();
    }

    @Test
    void concurrentDuplicateStillAllowsPendingDelivery() {
        candidate(today.minusDays(3));
        when(recorder.recordIfAbsent(eq(1L), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(recorder.exists(1L, today.minusDays(3))).thenReturn(true);
        when(recorder.pendingAfter(null, null)).thenReturn(List.of(
                SettlementAbandonmentAlert.pending(1L, today.minusDays(3), "saved")));

        assertThat(service.detectAbandoned(today).detectedCount()).isZero();
        verify(notifier).sendPending(1L, today.minusDays(3));
    }

    @Test
    void unrelatedIntegrityFailureIsNotSwallowed() {
        candidate(today.minusDays(3));
        var failure = new DataIntegrityViolationException("not a duplicate");
        when(recorder.recordIfAbsent(eq(1L), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.detectAbandoned(today)).isSameAs(failure);
        verifyNoInteractions(notifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "page", "criteria", "record"})
    void detectionFailureStillDeliversPendingAndPreservesOriginalFailure(String phase) {
        var failure = new IllegalStateException("detection failed: " + phase);
        switch (phase) {
            case "count" -> when(settlements.countBySettlementStatus(SettlementStatus.IN_PROGRESS))
                    .thenThrow(failure);
            case "page" -> when(settlements.findBySettlementStatusOrderBySettlementIdAsc(
                    SettlementStatus.IN_PROGRESS, PageRequest.of(0, 100))).thenThrow(failure);
            case "criteria" -> {
                var settlement = candidate(today.minusDays(3));
                when(criteria.resolveReferenceDate(settlement)).thenThrow(failure);
            }
            case "record" -> {
                candidate(today.minusDays(3));
                when(recorder.recordIfAbsent(eq(1L), any(), any())).thenThrow(failure);
            }
        }
        when(recorder.pendingAfter(null, null)).thenReturn(List.of(
                SettlementAbandonmentAlert.pending(9L, today.minusDays(5), "previous alert")));

        assertThatThrownBy(() -> service.detectAbandoned(today)).isSameAs(failure);

        verify(notifier).sendPending(9L, today.minusDays(5));
    }

    @Test
    void preservesBothFailuresWhenDetectionAndPendingLookupFail() {
        var detectionFailure = new IllegalStateException("detection failed");
        var deliveryFailure = new IllegalStateException("pending lookup failed");
        when(settlements.countBySettlementStatus(SettlementStatus.IN_PROGRESS)).thenThrow(detectionFailure);
        when(recorder.pendingAfter(null, null)).thenThrow(deliveryFailure);

        assertThatThrownBy(() -> service.detectAbandoned(today)).isSameAs(detectionFailure);
        assertThat(detectionFailure.getSuppressed()).containsExactly(deliveryFailure);
    }

    @Test
    void pendingLookupFailureIsNotReportedAsSuccessfulDetection() {
        var failure = new IllegalStateException("pending lookup failed");
        when(recorder.pendingAfter(null, null)).thenThrow(failure);
        assertThatThrownBy(() -> service.detectAbandoned(today)).isSameAs(failure);
    }

    @Test
    void failedDeliveryDoesNotBlockLaterPagesOrLoopOnSameRecord() {
        var firstDate = today.minusDays(5);
        var secondDate = today.minusDays(4);
        when(recorder.pendingAfter(null, null)).thenReturn(List.of(
                SettlementAbandonmentAlert.pending(1L, firstDate, "first")));
        when(recorder.pendingAfter(1L, firstDate)).thenReturn(List.of(
                SettlementAbandonmentAlert.pending(1L, secondDate, "second")));
        when(notifier.sendPending(1L, firstDate)).thenThrow(new RuntimeException("delivery failed"));

        service.detectAbandoned(today);

        verify(notifier).sendPending(1L, firstDate);
        verify(notifier).sendPending(1L, secondDate);
        verify(recorder).pendingAfter(1L, secondDate);
    }

    @Test
    void fullCandidatePageContinuesToNextPage() {
        var settlement = mock(Settlement.class);
        when(settlements.findBySettlementStatusOrderBySettlementIdAsc(
                SettlementStatus.IN_PROGRESS, PageRequest.of(0, 100)))
                .thenReturn(Collections.nCopies(100, settlement));
        service.detectAbandoned(today);
        verify(settlements).findBySettlementStatusOrderBySettlementIdAsc(
                SettlementStatus.IN_PROGRESS, PageRequest.of(1, 100));
    }

    private Settlement candidate(LocalDate referenceDate) {
        var settlement = mock(Settlement.class);
        lenient().when(settlement.getSettlementId()).thenReturn(1L);
        lenient().when(settlement.getTitle()).thenReturn("test");
        when(settlements.findBySettlementStatusOrderBySettlementIdAsc(
                SettlementStatus.IN_PROGRESS, PageRequest.of(0, 100))).thenReturn(List.of(settlement));
        when(criteria.resolveReferenceDate(settlement)).thenReturn(referenceDate);
        return settlement;
    }
}
