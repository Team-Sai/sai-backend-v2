package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentData;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentReader;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

        PaymentObligationView o1 =
                obligation(
                        1001L
                );

        PaymentObligationView o2 =
                obligation(
                        1002L
                );

        PaymentRecord r1 =
                paymentRecord(
                        1001L,
                        "3000"
                );

        PaymentRecord r2 =
                paymentRecord(
                        1001L,
                        "2000"
                );

        PaymentRecord r3 =
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

    @Test
    @DisplayName("여러 정산의 결제 데이터를 쿼리 3번으로 조회해 정산별로 나눈다")
    void readAllGroupsPaymentDataBySettlement() {
        SettlementParticipant p1 = participant(101L, 1L);
        SettlementParticipant p2 = participant(201L, 2L);

        PaymentObligationView o1 = obligation(1001L, 101L);
        PaymentObligationView o2 = obligation(2001L, 201L);

        PaymentRecord r1 = paymentRecord(1001L, "3000");
        PaymentRecord r2 = paymentRecord(1001L, "2000");
        PaymentRecord r3 = paymentRecord(2001L, "7000");

        given(
                settlementParticipantRepository.findBySettlementIdInAndStatus(
                        List.of(1L, 2L, 3L),
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(p1, p2)
        );

        given(
                paymentObligationQueryService.findByParticipantIds(
                        List.of(101L, 201L)
                )
        ).willReturn(
                List.of(o1, o2)
        );

        given(
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 2001L)
                )
        ).willReturn(
                List.of(r1, r2, r3)
        );

        Map<Long, SettlementPaymentData> result =
                settlementPaymentReader.readAll(
                        List.of(1L, 2L, 3L)
                );

        // ACTIVE 참여자가 없는 정산(3L)은 포함되지 않는다
        assertThat(result).containsOnlyKeys(1L, 2L);

        assertThat(result.get(1L).participants()).containsExactly(p1);
        assertThat(result.get(1L).obligations()).containsExactly(o1);
        assertThat(result.get(1L).paymentRecords()).containsExactly(r1, r2);
        assertThat(result.get(1L).paidAmountMap().get(1001L)).isEqualByComparingTo("5000");

        assertThat(result.get(2L).participants()).containsExactly(p2);
        assertThat(result.get(2L).obligations()).containsExactly(o2);
        assertThat(result.get(2L).paymentRecords()).containsExactly(r3);
        assertThat(result.get(2L).paidAmountMap().get(2001L)).isEqualByComparingTo("7000");

        verify(paymentRecordService, times(1))
                .findConfirmedRecordsByTargetIds(any(), anyList());
    }

    @Test
    @DisplayName("정산 ID가 비어 있으면 조회하지 않고 빈 Map을 반환한다")
    void readAllReturnsEmptyWhenSettlementIdsAreEmpty() {
        Map<Long, SettlementPaymentData> result =
                settlementPaymentReader.readAll(
                        List.of()
                );

        assertThat(result).isEmpty();

        verifyNoInteractions(
                settlementParticipantRepository,
                paymentObligationQueryService,
                paymentRecordService
        );
    }

    @Test
    @DisplayName("참여자는 있지만 납부의무가 없으면 참여자만 담고 결제 기록은 조회하지 않는다")
    void readAllSkipsPaymentRecordsWhenObligationsAreEmpty() {
        SettlementParticipant p1 = participant(101L, 1L);

        given(
                settlementParticipantRepository.findBySettlementIdInAndStatus(
                        List.of(1L),
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(p1)
        );

        given(
                paymentObligationQueryService.findByParticipantIds(
                        List.of(101L)
                )
        ).willReturn(
                List.of()
        );

        Map<Long, SettlementPaymentData> result =
                settlementPaymentReader.readAll(
                        List.of(1L)
                );

        assertThat(result.get(1L).participants()).containsExactly(p1);
        assertThat(result.get(1L).obligations()).isEmpty();
        assertThat(result.get(1L).paymentRecords()).isEmpty();
        assertThat(result.get(1L).paidAmountMap()).isEmpty();

        verifyNoInteractions(paymentRecordService);
    }

    private SettlementParticipant participant(
            Long participantId,
            Long settlementId
    ) {
        SettlementParticipant participant =
                participant(participantId);

        when(
                participant.getSettlement()
        ).thenReturn(
                Settlement.builder()
                        .settlementId(settlementId)
                        .build()
        );

        return participant;
    }

    private PaymentObligationView obligation(
            Long obligationId,
            Long participantId
    ) {
        return new PaymentObligationView(obligationId, participantId, BigDecimal.ZERO,
                org.teamsai.saibackend.domain.payment.type.PaymentStatus.UNPAID,
                org.teamsai.saibackend.domain.payment.type.ObligationStatus.ACTIVE, null);
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

    private PaymentObligationView obligation(
            Long obligationId
    ) {
        return new PaymentObligationView(obligationId, 101L, BigDecimal.ZERO,
                org.teamsai.saibackend.domain.payment.type.PaymentStatus.UNPAID,
                org.teamsai.saibackend.domain.payment.type.ObligationStatus.ACTIVE, null);
    }

    private PaymentRecord paymentRecord(
            Long obligationId,
            String amount
    ) {
        PaymentRecord paymentRecord =
                mock(PaymentRecord.class);

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
