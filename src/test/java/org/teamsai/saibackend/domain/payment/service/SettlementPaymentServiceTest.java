package org.teamsai.saibackend.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.SourceType;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.LongStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentService 단위 테스트")
class SettlementPaymentServiceTest {

    @Mock
    private PaymentObligationRepository paymentObligationRepository;

    @Mock
    private PaymentRecordService paymentRecordService;

    @Test
    void writesOffOnlyLockedTargetsAcrossChunks() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        List<Long> ids = LongStream.rangeClosed(1, 1200).boxed().toList();
        List<List<Long>> queriedChunks = new ArrayList<>();
        List<PaymentObligationEntity> targets = new ArrayList<>();
        given(paymentObligationRepository.findWriteOffTargetsForUpdate(
                anyList(), eq(ObligationStatus.ACTIVE),
                eq(List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID))))
                .willAnswer(invocation -> {
                    List<Long> chunk = invocation.getArgument(0);
                    queriedChunks.add(List.copyOf(chunk));
                    var target = new PaymentObligationEntity(chunk.get(0), BigDecimal.TEN);
                    targets.add(target);
                    return List.of(target);
                });

        assertThat(service.writeOffOneBatch(ids)).isEqualTo(3);
        assertThat(queriedChunks).containsExactly(
                ids.subList(0, 500), ids.subList(500, 1000), ids.subList(1000, 1200));
        assertThat(targets).allSatisfy(target ->
                assertThat(target.getObligationStatus()).isEqualTo(ObligationStatus.WRITTEN_OFF));
        verifyNoInteractions(paymentRecordService);
    }

    @Test
    void writesOffExactly500CandidatesWithOneQuery() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        List<Long> ids = LongStream.rangeClosed(1, 500).boxed().toList();
        var target = new PaymentObligationEntity(1L, BigDecimal.TEN);
        given(paymentObligationRepository.findWriteOffTargetsForUpdate(
                ids, ObligationStatus.ACTIVE,
                List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID)))
                .willReturn(List.of(target));

        assertThat(service.writeOffOneBatch(ids)).isEqualTo(1);
        assertThat(target.getObligationStatus()).isEqualTo(ObligationStatus.WRITTEN_OFF);
        verify(paymentObligationRepository).findWriteOffTargetsForUpdate(
                ids, ObligationStatus.ACTIVE,
                List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID));
        verifyNoMoreInteractions(paymentObligationRepository);
    }

    @Test
    void skipsEmptyWriteOffBatch() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        assertThat(service.writeOffOneBatch(List.of())).isZero();
        verifyNoInteractions(paymentObligationRepository, paymentRecordService);
    }

    @Test
    void returnsZeroWhenCandidatesAreNoLongerEligible() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        given(paymentObligationRepository.findWriteOffTargetsForUpdate(
                List.of(1L), ObligationStatus.ACTIVE,
                List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID)))
                .willReturn(List.of());
        assertThat(service.writeOffOneBatch(List.of(1L))).isZero();
    }

    @Test
    void marksAllActiveUnpaidTargetsOverdue() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        var first = new PaymentObligationEntity(1L, BigDecimal.TEN);
        var second = new PaymentObligationEntity(2L, BigDecimal.TEN);
        second.changePaymentStatus(PaymentStatus.PARTIALLY_PAID);
        var since = LocalDateTime.of(2026, 2, 2, 0, 0);
        given(paymentObligationRepository.findUnpaidByParticipantIds(
                List.of(1L, 2L), List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID),
                ObligationStatus.ACTIVE)).willReturn(List.of(first, second));

        assertThat(service.markOverdueByParticipantIds(List.of(1L, 2L), since)).isEqualTo(2);
        assertThat(first.getOverdueSince()).isEqualTo(since);
        assertThat(second.getOverdueSince()).isEqualTo(since);
    }

    @Test
    void returnsZeroWhenNoUnpaidObligationsExist() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        given(paymentObligationRepository.findUnpaidByParticipantIds(
                List.of(1L), List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID),
                ObligationStatus.ACTIVE)).willReturn(List.of());
        assertThat(service.markOverdueByParticipantIds(List.of(1L), LocalDateTime.of(2026, 2, 2, 0, 0)))
                .isZero();
    }

    @Test
    void skipsOverdueLookupWithoutParticipants() {
        var service = new SettlementPaymentService(paymentObligationRepository, paymentRecordService);
        assertThat(service.markOverdueByParticipantIds(List.of(), LocalDateTime.of(2026, 2, 2, 0, 0)))
                .isZero();
        verifyNoInteractions(paymentObligationRepository);
    }

    @Test
    @DisplayName("초과입금은 현재 잔여금액까지만 수동 납부로 반영한다")
    void limitsExcessAmountToCurrentRemainingAmount() {
        SettlementPaymentService settlementPaymentService =
                new SettlementPaymentService(
                        paymentObligationRepository,
                        paymentRecordService
                );
        PaymentObligationEntity obligation =
                new PaymentObligationEntity(
                        20L,
                        new BigDecimal("100000")
                );
        obligation.changePaymentStatus(PaymentStatus.PARTIALLY_PAID);

        given(paymentRecordService.existsByBankTransactionId(101L))
                .willReturn(false);
        given(paymentObligationRepository.findByIdForUpdate(10L))
                .willReturn(Optional.of(obligation));
        given(paymentRecordService.sumConfirmedAmountByTarget(
                PaymentTargetType.SETTLEMENT,
                10L
        )).willReturn(new BigDecimal("70000"));
        settlementPaymentService.applyManuallyMatchedPayment(
                10L,
                101L,
                new BigDecimal("32000")
        );

        verify(paymentRecordService).createConfirmedRecord(
                101L,
                PaymentTargetType.SETTLEMENT,
                10L,
                new BigDecimal("30000"),
                SourceType.MANUAL
        );
        assertThat(obligation.getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("이미 완납된 정산 대상에는 수동 납부를 반영하지 않는다")
    void rejectsManuallyMatchedPaymentForPaidObligation() {
        SettlementPaymentService settlementPaymentService =
                new SettlementPaymentService(
                        paymentObligationRepository,
                        paymentRecordService
                );
        PaymentObligationEntity obligation =
                new PaymentObligationEntity(
                        20L,
                        new BigDecimal("100000")
                );
        obligation.changePaymentStatus(PaymentStatus.PAID);

        given(paymentRecordService.existsByBankTransactionId(101L))
                .willReturn(false);
        given(paymentObligationRepository.findByIdForUpdate(10L))
                .willReturn(Optional.of(obligation));

        assertThatThrownBy(() ->
                settlementPaymentService.applyManuallyMatchedPayment(
                        10L,
                        101L,
                        new BigDecimal("10000")
                )
        ).isInstanceOfSatisfying(
                org.teamsai.saibackend.global.exception.DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_OBLIGATION_NOT_ACTIVE)
        );
    }
}
