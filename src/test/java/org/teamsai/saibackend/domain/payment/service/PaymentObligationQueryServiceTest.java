package org.teamsai.saibackend.domain.payment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentObligationQueryServiceTest {
    @Mock
    private PaymentObligationRepository repository;

    @Test
    void returnsObligationReadModelWithoutExposingEntity() {
        var service = new PaymentObligationQueryService(repository);
        var entity = new PaymentObligationEntity(42L, new BigDecimal("120000"));
        when(repository.findByParticipantIdIn(List.of(42L))).thenReturn(List.of(entity));

        var obligations = service.findByParticipantIds(List.of(42L));

        assertThat(obligations).containsExactly(new PaymentObligationView(
                entity.getPaymentObligationId(),
                42L,
                new BigDecimal("120000"),
                entity.getPaymentStatus(),
                entity.getObligationStatus(),
                entity.getOverdueSince()
        ));
    }

    @Test
    void mapsLatestAmountsByParticipantRegardlessOfResultOrder() {
        var service = new PaymentObligationQueryService(repository);
        var statuses = List.of(ObligationStatus.ACTIVE, ObligationStatus.EXCLUDED,
                ObligationStatus.CANCELLED, ObligationStatus.WRITTEN_OFF);
        when(repository.findLatestByParticipantIdsAndObligationStatuses(List.of(1L, 2L, 3L), statuses))
                .thenReturn(List.of(new PaymentObligationEntity(2L, new BigDecimal("120000")),
                        new PaymentObligationEntity(1L, new BigDecimal("80000"))));

        var amounts = service.findLatestExpectedAmountsByParticipantIdsAndStatuses(List.of(1L, 2L, 3L), statuses);

        assertThat(amounts).hasSize(2)
                .containsEntry(1L, new BigDecimal("80000"))
                .containsEntry(2L, new BigDecimal("120000"))
                .doesNotContainKey(3L);
    }

    @Test
    void skipsQueriesWhenNoParticipantsOrStatusesAreProvided() {
        var service = new PaymentObligationQueryService(repository);
        assertThat(service.findByParticipantIds(List.of())).isEmpty();
        assertThat(service.findLatestActiveByParticipantIds(List.of())).isEmpty();
        assertThat(service.findLatestExpectedAmountsByParticipantIdsAndStatuses(
                List.of(1L), List.of())).isEmpty();
        assertThat(service.findSettlementIdsByObligationIds(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }
}
