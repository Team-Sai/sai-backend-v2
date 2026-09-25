package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateRecurringSettlementResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@DisplayName("SettlementAssembler 단위 테스트")
class SettlementAssemblerTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long OWNER_ID = 10L;

    private SettlementParticipant participant(Long participantId, Long userId, String name) {
        SettlementParticipant participant = mock(SettlementParticipant.class);
        User user = mock(User.class);
        lenient().when(participant.getParticipantId()).thenReturn(participantId);
        lenient().when(participant.getUser()).thenReturn(user);
        lenient().when(user.getUserId()).thenReturn(userId);
        lenient().when(user.getName()).thenReturn(name);
        return participant;
    }

    private PaymentObligationEntity obligation(
            Long obligationId, Long participantId, long expectedAmount, ObligationStatus status
    ) {
        PaymentObligationEntity obligation = mock(PaymentObligationEntity.class);
        lenient().when(obligation.getPaymentObligationId()).thenReturn(obligationId);
        lenient().when(obligation.getParticipantId()).thenReturn(participantId);
        lenient().when(obligation.getExpectedAmount()).thenReturn(BigDecimal.valueOf(expectedAmount));
        lenient().when(obligation.getObligationStatus()).thenReturn(status);
        return obligation;
    }

    private PaymentRecordEntity paymentRecord(long amount, LocalDateTime recordedAt) {
        PaymentRecordEntity record = mock(PaymentRecordEntity.class);
        lenient().when(record.getAmount()).thenReturn(BigDecimal.valueOf(amount));
        lenient().when(record.getRecordedAt()).thenReturn(recordedAt);
        return record;
    }

    @Nested
    @DisplayName("정기 정산 생성 응답 조립")
    class ToCreateRecurringSettlementResponse {

        @Test
        @DisplayName("RecurringSettlement과 첫 회차 Settlement을 조합한다")
        void combinesRecurringSettlementAndFirstSettlement() {
            RecurringSettlement recurring = RecurringSettlement.builder()
                    .recurringSettlementId(100L)
                    .startDate(LocalDate.of(2026, 1, 1))
                    .endDate(LocalDate.of(2026, 12, 1))
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                    .build();
            Settlement firstSettlement = Settlement.builder()
                    .settlementId(200L)
                    .settlementType(SettlementType.RECURRING)
                    .title("월세 정산")
                    .build();

            CreateRecurringSettlementResponse result =
                    SettlementAssembler.toCreateRecurringSettlementResponse(recurring, firstSettlement);

            assertThat(result.getRecurringSettlementId()).isEqualTo(100L);
            assertThat(result.getFirstSettlementId()).isEqualTo(200L);
            assertThat(result.getSettlementType()).isEqualTo(SettlementType.RECURRING);
            assertThat(result.getTitle()).isEqualTo("월세 정산");
            assertThat(result.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(result.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        }
    }

    @Nested
    @DisplayName("정산 의무 응답 조립")
    class ToObligationResponse {

        @Test
        @DisplayName("결제 기록이 없으면 UNPAID, 전액 남은 금액으로 계산한다")
        void unpaidWhenNoRecords() {
            SettlementParticipant participant = participant(1L, OWNER_ID, "채빈");
            PaymentObligationEntity obligation = obligation(11L, 1L, 10_000, ObligationStatus.ACTIVE);

            SettlementPaymentObligationResponse result =
                    SettlementAssembler.toObligationResponse(obligation, participant, List.of());

            assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
            assertThat(result.getPaidAmount()).isEqualByComparingTo("0");
            assertThat(result.getRemainingAmount()).isEqualByComparingTo("10000");
            assertThat(result.getLatestPaymentAt()).isNull();
            assertThat(result.getParticipantName()).isEqualTo("채빈");
        }

        @Test
        @DisplayName("일부만 결제되면 PARTIALLY_PAID, 가장 최근 결제일을 사용한다")
        void partiallyPaidUsesLatestPaymentDate() {
            SettlementParticipant participant = participant(1L, OWNER_ID, "채빈");
            PaymentObligationEntity obligation = obligation(11L, 1L, 10_000, ObligationStatus.ACTIVE);
            LocalDateTime earlier = LocalDateTime.of(2026, 8, 1, 0, 0);
            LocalDateTime later = LocalDateTime.of(2026, 8, 10, 0, 0);
            List<PaymentRecordEntity> records = List.of(
                    paymentRecord(3_000, earlier),
                    paymentRecord(2_000, later)
            );

            SettlementPaymentObligationResponse result =
                    SettlementAssembler.toObligationResponse(obligation, participant, records);

            assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
            assertThat(result.getPaidAmount()).isEqualByComparingTo("5000");
            assertThat(result.getRemainingAmount()).isEqualByComparingTo("5000");
            assertThat(result.getLatestPaymentAt()).isEqualTo(later);
        }

        @Test
        @DisplayName("전액 결제되면 PAID, 남은 금액은 0이다")
        void fullyPaid() {
            SettlementParticipant participant = participant(1L, OWNER_ID, "채빈");
            PaymentObligationEntity obligation = obligation(11L, 1L, 10_000, ObligationStatus.ACTIVE);
            List<PaymentRecordEntity> records = List.of(paymentRecord(10_000, LocalDateTime.now()));

            SettlementPaymentObligationResponse result =
                    SettlementAssembler.toObligationResponse(obligation, participant, records);

            assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(result.getRemainingAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("정산 상태 요약 조립")
    class ToPaymentStatusResponse {

        private Settlement settlement() {
            return Settlement.builder().settlementId(SETTLEMENT_ID).build();
        }

        @Test
        @DisplayName("obligation들의 금액/상태 개수를 집계한다")
        void aggregatesAmountsAndCounts() {
            List<SettlementPaymentObligationResponse> obligations = List.of(
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(10_000))
                            .paidAmount(BigDecimal.valueOf(10_000))
                            .remainingAmount(BigDecimal.ZERO)
                            .paymentStatus(PaymentStatus.PAID)
                            .obligationStatus(ObligationStatus.ACTIVE)
                            .build(),
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(10_000))
                            .paidAmount(BigDecimal.valueOf(4_000))
                            .remainingAmount(BigDecimal.valueOf(6_000))
                            .paymentStatus(PaymentStatus.PARTIALLY_PAID)
                            .obligationStatus(ObligationStatus.ACTIVE)
                            .build(),
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(10_000))
                            .paidAmount(BigDecimal.ZERO)
                            .remainingAmount(BigDecimal.valueOf(10_000))
                            .paymentStatus(PaymentStatus.UNPAID)
                            .obligationStatus(ObligationStatus.ACTIVE)
                            .build()
            );

            SettlementPaymentStatusResponse result =
                    SettlementAssembler.toPaymentStatusResponse(settlement(), obligations);

            assertThat(result.getSettlementId()).isEqualTo(SETTLEMENT_ID);
            assertThat(result.getTotalExpectedAmount()).isEqualByComparingTo("30000");
            assertThat(result.getTotalPaidAmount()).isEqualByComparingTo("14000");
            assertThat(result.getTotalRemainingAmount()).isEqualByComparingTo("16000");
            assertThat(result.getPaidCount()).isEqualTo(1);
            assertThat(result.getPartiallyPaidCount()).isEqualTo(1);
            assertThat(result.getUnpaidCount()).isEqualTo(1);
            assertThat(result.getProgressRate()).isEqualByComparingTo("46.67");
            assertThat(result.isClosable()).isFalse();
        }

        @Test
        @DisplayName("WRITTEN_OFF 상각분은 기대액 전체를 해결된 것으로 계산한다")
        void writtenOffCountsAsFullyResolved() {
            List<SettlementPaymentObligationResponse> obligations = List.of(
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(10_000))
                            .paidAmount(BigDecimal.ZERO)
                            .remainingAmount(BigDecimal.valueOf(10_000))
                            .paymentStatus(PaymentStatus.UNPAID)
                            .obligationStatus(ObligationStatus.WRITTEN_OFF)
                            .build()
            );

            SettlementPaymentStatusResponse result =
                    SettlementAssembler.toPaymentStatusResponse(settlement(), obligations);

            assertThat(result.getProgressRate()).isEqualByComparingTo("100.00");
            assertThat(result.isClosable()).isTrue();
        }

        @Test
        @DisplayName("기대 금액이 0이면 진행률은 0이다")
        void zeroExpectedAmountGivesZeroProgress() {
            SettlementPaymentStatusResponse result =
                    SettlementAssembler.toPaymentStatusResponse(settlement(), List.of());

            assertThat(result.getProgressRate()).isEqualByComparingTo("0");
            assertThat(result.isClosable()).isFalse();
        }
    }

    @Nested
    @DisplayName("완결 여부 판단")
    class IsFullyResolved {

        @Test
        @DisplayName("모든 obligation이 완납되면 true")
        void trueWhenAllPaid() {
            List<SettlementPaymentObligationResponse> obligations = List.of(
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(5_000))
                            .paidAmount(BigDecimal.valueOf(5_000))
                            .obligationStatus(ObligationStatus.ACTIVE)
                            .build()
            );

            assertThat(SettlementAssembler.isFullyResolved(obligations)).isTrue();
        }

        @Test
        @DisplayName("하나라도 미납이면 false")
        void falseWhenAnyUnpaid() {
            List<SettlementPaymentObligationResponse> obligations = List.of(
                    SettlementPaymentObligationResponse.builder()
                            .expectedAmount(BigDecimal.valueOf(5_000))
                            .paidAmount(BigDecimal.ZERO)
                            .obligationStatus(ObligationStatus.ACTIVE)
                            .build()
            );

            assertThat(SettlementAssembler.isFullyResolved(obligations)).isFalse();
        }

        @Test
        @DisplayName("obligation이 없으면 false")
        void falseWhenEmpty() {
            assertThat(SettlementAssembler.isFullyResolved(List.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("결제 이력 응답 조립")
    class ToPaymentHistoryResponse {

        @Test
        @DisplayName("은행 거래가 있으면 거래처명/외부거래ID를 채운다")
        void includesTransactionDetailsWhenPresent() {
            PaymentRecordEntity record = mock(PaymentRecordEntity.class);
            lenient().when(record.getPaymentRecordId()).thenReturn(1L);
            lenient().when(record.getRecordedAt()).thenReturn(LocalDateTime.of(2026, 8, 1, 0, 0));
            lenient().when(record.getAmount()).thenReturn(BigDecimal.valueOf(5_000));
            lenient().when(record.getSourceType()).thenReturn(SourceType.AUTO_MATCH);
            lenient().when(record.getBankTransactionId()).thenReturn(99L);

            BankTransactionEntity transaction = mock(BankTransactionEntity.class);
            lenient().when(transaction.getCounterpartyName()).thenReturn("홍길동");
            lenient().when(transaction.getExternalTransactionId()).thenReturn("EXT-123");

            SettlementPaymentHistoryResponse result =
                    SettlementAssembler.toPaymentHistoryResponse(record, transaction, "채빈");

            assertThat(result.payerName()).isEqualTo("채빈");
            assertThat(result.counterpartyName()).isEqualTo("홍길동");
            assertThat(result.externalTransactionId()).isEqualTo("EXT-123");
        }

        @Test
        @DisplayName("은행 거래가 없으면 거래처명/외부거래ID는 null이다")
        void handlesMissingTransaction() {
            PaymentRecordEntity record = mock(PaymentRecordEntity.class);
            lenient().when(record.getPaymentRecordId()).thenReturn(1L);
            lenient().when(record.getAmount()).thenReturn(BigDecimal.valueOf(5_000));

            SettlementPaymentHistoryResponse result =
                    SettlementAssembler.toPaymentHistoryResponse(record, null, "채빈");

            assertThat(result.counterpartyName()).isNull();
            assertThat(result.externalTransactionId()).isNull();
        }
    }
}
