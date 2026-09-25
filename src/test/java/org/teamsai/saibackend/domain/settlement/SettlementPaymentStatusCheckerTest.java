package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentStatusChecker 단위 테스트")
class SettlementPaymentStatusCheckerTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long OWNER_ID = 10L;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @Mock
    private PaymentObligationRepository paymentObligationRepository;

    @Mock
    private PaymentRecordRepository paymentRecordRepository;

    @InjectMocks
    private SettlementPaymentStatusChecker checker;

    @Test
    @DisplayName("모든 납부의무가 완납되면 true를 반환한다")
    void areAllObligationsResolvedReturnsTrueWhenAllPaid() {
        SettlementParticipant participant =
                participant(101L);

        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity r1 =
                paymentRecord(1001L, 10000);

        PaymentRecordEntity r2 =
                paymentRecord(1002L, 20000);

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationRepository.findByParticipantIdIn(
                        List.of(101L)
                )
        ).willReturn(
                List.of(o1, o2)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(r1, r2)
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("미납이지만 WRITTEN_OFF 상태이면 해결된 것으로 판단한다")
    void areAllObligationsResolvedReturnsTrueWhenWrittenOff() {
        SettlementParticipant participant =
                participant(101L);

        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.WRITTEN_OFF
                );

        PaymentRecordEntity r1 =
                paymentRecord(1001L, 10000);

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationRepository.findByParticipantIdIn(
                        List.of(101L)
                )
        ).willReturn(
                List.of(o1, o2)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(r1)
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("해결되지 않은 납부의무가 하나라도 있으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenNotAllResolved() {
        SettlementParticipant participant =
                participant(101L);

        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity r1 =
                paymentRecord(1001L, 10000);

        PaymentRecordEntity r2 =
                paymentRecord(1002L, 10000);

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationRepository.findByParticipantIdIn(
                        List.of(101L)
                )
        ).willReturn(
                List.of(o1, o2)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(r1, r2)
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("납부의무가 하나도 없으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenObligationsAreEmpty() {
        SettlementParticipant participant =
                participant(101L);

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationRepository.findByParticipantIdIn(
                        List.of(101L)
                )
        ).willReturn(
                List.of()
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("ACTIVE 참여자가 없으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenParticipantsAreEmpty() {
        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of()
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isFalse();
    }

    private SettlementParticipant participant(
            Long participantId
    ) {
        SettlementParticipant participant =
                mock(SettlementParticipant.class);

        lenient()
                .when(participant.getParticipantId())
                .thenReturn(participantId);

        return participant;
    }

    private PaymentObligationEntity obligation(
            Long obligationId,
            Long participantId,
            long expectedAmount,
            ObligationStatus obligationStatus
    ) {
        PaymentObligationEntity obligation =
                mock(PaymentObligationEntity.class);

        lenient()
                .when(obligation.getPaymentObligationId())
                .thenReturn(obligationId);

        lenient()
                .when(obligation.getParticipantId())
                .thenReturn(participantId);

        lenient()
                .when(obligation.getExpectedAmount())
                .thenReturn(
                        BigDecimal.valueOf(expectedAmount)
                );

        lenient()
                .when(obligation.getObligationStatus())
                .thenReturn(obligationStatus);

        return obligation;
    }

    private PaymentRecordEntity paymentRecord(
            Long obligationId,
            long amount
    ) {
        PaymentRecordEntity paymentRecord =
                mock(PaymentRecordEntity.class);

        lenient()
                .when(paymentRecord.getTargetId())
                .thenReturn(obligationId);

        lenient()
                .when(paymentRecord.getAmount())
                .thenReturn(
                        BigDecimal.valueOf(amount)
                );

        return paymentRecord;
    }
}