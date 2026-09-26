package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentData;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentReader;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentReader 단위 테스트")
class SettlementPaymentReaderTest {

    private static final Long SETTLEMENT_ID = 1L;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @Mock
    private PaymentObligationQueryService paymentObligationQueryService;

    @Mock
    private PaymentRecordService paymentRecordService;

    @InjectMocks
    private SettlementPaymentReader settlementPaymentReader;

    @Test
    @DisplayName("ACTIVE 참여자가 없으면 빈 결제 데이터를 반환한다")
    void returnsEmptyWhenParticipantsAreEmpty() {
        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of()
        );

        SettlementPaymentData result =
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                );

        assertThat(result.participants())
                .isEmpty();

        assertThat(result.obligations())
                .isEmpty();

        assertThat(result.paymentRecords())
                .isEmpty();

        assertThat(result.paidAmountMap())
                .isEmpty();

        verifyNoInteractions(
                paymentObligationQueryService,
                paymentRecordService
        );
    }

    @Test
    @DisplayName("납부 의무가 없으면 참여자만 포함하고 결제 데이터는 비어 있다")
    void returnsParticipantsWhenObligationsAreEmpty() {
        SettlementParticipant participant =
                participant(
                        101L
                );

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationQueryService.findByParticipantIds(
                        List.of(101L)
                )
        ).willReturn(
                List.of()
        );

        SettlementPaymentData result =
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                );

        assertThat(result.participants())
                .containsExactly(participant);

        assertThat(result.obligations())
                .isEmpty();

        assertThat(result.paymentRecords())
                .isEmpty();

        assertThat(result.paidAmountMap())
                .isEmpty();

        verifyNoInteractions(
                paymentRecordService
        );
    }

    @Test
    @DisplayName("확정 결제 기록을 obligation별로 합산한다")
    void aggregatesPaidAmountByObligation() {
        SettlementParticipant participant =
                participant(
                        101L
                );

        PaymentObligationEntity o1 =
                obligation(
                        1001L
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L
                );

        PaymentRecordEntity r1 =
                paymentRecord(
                        1001L,
                        "3000"
                );

        PaymentRecordEntity r2 =
                paymentRecord(
                        1001L,
                        "2000"
                );

        PaymentRecordEntity r3 =
                paymentRecord(
                        1002L,
                        "7000"
                );

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(participant)
        );

        given(
                paymentObligationQueryService.findByParticipantIds(
                        List.of(101L)
                )
        ).willReturn(
                List.of(o1, o2)
        );

        given(
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L)
                )
        ).willReturn(
                List.of(r1, r2, r3)
        );

        SettlementPaymentData result =
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                );

        assertThat(result.participants())
                .containsExactly(participant);

        assertThat(result.obligations())
                .containsExactly(o1, o2);

        assertThat(result.paymentRecords())
                .containsExactly(r1, r2, r3);

        assertThat(
                result.paidAmountMap().get(1001L)
        ).isEqualByComparingTo(
                "5000"
        );

        assertThat(
                result.paidAmountMap().get(1002L)
        ).isEqualByComparingTo(
                "7000"
        );

        verify(paymentRecordService)
                .findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L)
                );
    }

    private SettlementParticipant participant(
            Long participantId
    ) {
        SettlementParticipant participant =
                mock(SettlementParticipant.class);

        when(
                participant.getParticipantId()
        ).thenReturn(
                participantId
        );

        return participant;
    }

    private PaymentObligationEntity obligation(
            Long obligationId
    ) {
        PaymentObligationEntity obligation =
                mock(PaymentObligationEntity.class);

        when(
                obligation.getPaymentObligationId()
        ).thenReturn(
                obligationId
        );

        return obligation;
    }

    private PaymentRecordEntity paymentRecord(
            Long obligationId,
            String amount
    ) {
        PaymentRecordEntity paymentRecord =
                mock(PaymentRecordEntity.class);

        when(
                paymentRecord.getTargetId()
        ).thenReturn(
                obligationId
        );

        when(
                paymentRecord.getAmount()
        ).thenReturn(
                new BigDecimal(amount)
        );

        return paymentRecord;
    }
}
