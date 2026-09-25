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
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusQueryService;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentStatusQueryService 단위 테스트")
class SettlementPaymentStatusQueryServiceTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long MEMBER_ID = 20L;
    private static final Long OTHER_USER_ID = 30L;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @Mock
    private PaymentObligationRepository paymentObligationRepository;

    @Mock
    private PaymentRecordRepository paymentRecordRepository;

    @Mock
    private SettlementValidator settlementValidator;

    @InjectMocks
    private SettlementPaymentStatusQueryService paymentStatusService;

    @Test
    @DisplayName("납부의무별 금액을 합산하고 진행률을 계산한다")
    void getPaymentStatusCalculatesTotalsAndProgressRate() {
        Settlement settlement = createSettlement();
        SettlementParticipant p1 = participant(101L, OWNER_ID);
        SettlementParticipant p2 = participant(102L, MEMBER_ID);

        PaymentObligationEntity o1 =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o2 =
                obligation(1002L, 102L, 20000, ObligationStatus.ACTIVE);

        PaymentRecordEntity r1 = paymentRecord(1001L, 5000);
        PaymentRecordEntity r2 = paymentRecord(1002L, 20000);

        given(
                settlementRepository.findById(SETTLEMENT_ID)
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        SETTLEMENT_ID,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(p1, p2)
        );

        given(
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        List.of(101L, 102L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
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

        SettlementPaymentStatusResponse response =
                paymentStatusService.getPaymentStatus(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.getTotalExpectedAmount())
                .isEqualByComparingTo("30000");
        assertThat(response.getTotalPaidAmount())
                .isEqualByComparingTo("25000");
        assertThat(response.getTotalRemainingAmount())
                .isEqualByComparingTo("5000");
        assertThat(response.getProgressRate())
                .isEqualByComparingTo("83.33");
        assertThat(response.isClosable())
                .isFalse();

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        OWNER_ID
                );
    }

    @Test
    @DisplayName("납부의무 상태별 인원수를 집계한다")
    void getPaymentStatusCountsObligationsByPaymentStatus() {
        Settlement settlement = createSettlement();
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity o1 =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o2 =
                obligation(1002L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o3 =
                obligation(1003L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o4 =
                obligation(1004L, 101L, 10000, ObligationStatus.ACTIVE);

        PaymentRecordEntity r1 = paymentRecord(1001L, 10000);
        PaymentRecordEntity r2 = paymentRecord(1002L, 4000);
        PaymentRecordEntity r3 = paymentRecord(1003L, 4000);

        given(
                settlementRepository.findById(SETTLEMENT_ID)
        ).willReturn(
                Optional.of(settlement)
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
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        List.of(101L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                )
        ).willReturn(
                List.of(o1, o2, o3, o4)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L, 1002L, 1003L, 1004L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(r1, r2, r3)
        );

        SettlementPaymentStatusResponse response =
                paymentStatusService.getPaymentStatus(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.getPaidCount())
                .isEqualTo(1L);
        assertThat(response.getPartiallyPaidCount())
                .isEqualTo(2L);
        assertThat(response.getUnpaidCount())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("모든 납부의무가 PAID이면 마감 가능 상태가 된다")
    void getPaymentStatusIsClosableWhenAllObligationsArePaid() {
        Settlement settlement = createSettlement();
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity obligation =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);

        PaymentRecordEntity record = paymentRecord(1001L, 10000);

        given(
                settlementRepository.findById(SETTLEMENT_ID)
        ).willReturn(
                Optional.of(settlement)
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
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        List.of(101L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                )
        ).willReturn(
                List.of(obligation)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(record)
        );

        SettlementPaymentStatusResponse response =
                paymentStatusService.getPaymentStatus(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.isClosable())
                .isTrue();
        assertThat(response.getProgressRate())
                .isEqualByComparingTo("100.00");

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        OWNER_ID
                );
    }

    @Test
    @DisplayName("초과 납부가 있어도 진행률은 100을 넘지 않고 마감할 수 없다")
    void getPaymentStatusIsNotClosableWhenPaymentExceedsExpectedAmount() {
        Settlement settlement = createSettlement();
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity obligation =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);

        PaymentRecordEntity record = paymentRecord(1001L, 11000);

        given(
                settlementRepository.findById(SETTLEMENT_ID)
        ).willReturn(
                Optional.of(settlement)
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
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        List.of(101L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                )
        ).willReturn(
                List.of(obligation)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(record)
        );

        SettlementPaymentStatusResponse response =
                paymentStatusService.getPaymentStatus(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.getProgressRate())
                .isEqualByComparingTo("100.00");
        assertThat(response.getTotalRemainingAmount())
                .isEqualByComparingTo("0");
        assertThat(response.isClosable())
                .isFalse();
    }

    @Test
    @DisplayName("정산이 없으면 현황 조회에 실패한다")
    void getPaymentStatusFailsWhenSettlementDoesNotExist() {
        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () ->
                        paymentStatusService.getPaymentStatus(
                                SETTLEMENT_ID,
                                OWNER_ID
                        )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                        )
        );

        verify(
                settlementValidator,
                never()
        ).validateAccessibleUser(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("ACTIVE 정산 참여자는 납부 현황을 조회할 수 있다")
    void getPaymentStatusSucceedsForMember() {
        Settlement settlement = createSettlement();
        SettlementParticipant participant = participant(101L, MEMBER_ID);

        PaymentObligationEntity obligation =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);

        PaymentRecordEntity record = paymentRecord(1001L, 5000);

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
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
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        List.of(101L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                )
        ).willReturn(
                List.of(obligation)
        );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of(1001L),
                        RecordStatus.CONFIRMED
                )
        ).willReturn(
                List.of(record)
        );

        SettlementPaymentStatusResponse response =
                paymentStatusService.getPaymentStatus(
                        SETTLEMENT_ID,
                        MEMBER_ID
                );

        assertThat(response)
                .isNotNull();
        assertThat(response.getTotalExpectedAmount())
                .isEqualByComparingTo("10000");

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        MEMBER_ID
                );

        verify(paymentObligationRepository)
                .findByParticipantIdsAndObligationStatuses(
                        List.of(101L),
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                );
    }

    @Test
    @DisplayName("정산과 관계없는 사용자는 납부 현황을 조회할 수 없다")
    void getPaymentStatusFailsWhenUserHasNoAccess() {
        Settlement settlement = createSettlement();

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        doThrow(
                SettlementErrorCode
                        .SETTLEMENT_ACCESS_DENIED
                        .toException()
        ).when(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        OTHER_USER_ID
                );

        assertThatThrownBy(
                () ->
                        paymentStatusService.getPaymentStatus(
                                SETTLEMENT_ID,
                                OTHER_USER_ID
                        )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode
                                        .SETTLEMENT_ACCESS_DENIED
                        )
        );

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        OTHER_USER_ID
                );

        verifyNoInteractions(
                settlementParticipantRepository,
                paymentObligationRepository,
                paymentRecordRepository
        );
    }

    @Test
    @DisplayName("모든 납부의무가 해결되면(완납 또는 상각) true를 반환한다")
    void areAllObligationsResolvedReturnsTrueWhenAllPaid() {
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity o1 =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o2 =
                obligation(1002L, 101L, 20000, ObligationStatus.ACTIVE);

        PaymentRecordEntity r1 = paymentRecord(1001L, 10000);
        PaymentRecordEntity r2 = paymentRecord(1002L, 20000);

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
                paymentStatusService
                        .areAllObligationsResolved(
                                SETTLEMENT_ID
                        );

        assertThat(result)
                .isTrue();
    }

    @Test
    @DisplayName("미납이지만 상각(WRITTEN_OFF)된 납부의무는 해결된 것으로 본다")
    void areAllObligationsResolvedReturnsTrueWhenWrittenOff() {
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity o1 =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o2 =
                obligation(1002L, 101L, 20000, ObligationStatus.WRITTEN_OFF);

        PaymentRecordEntity r1 = paymentRecord(1001L, 10000);

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
                paymentStatusService
                        .areAllObligationsResolved(
                                SETTLEMENT_ID
                        );

        assertThat(result)
                .isTrue();
    }

    @Test
    @DisplayName("완료되지 않은 납부의무가 하나라도 있으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenNotAllResolved() {
        SettlementParticipant participant = participant(101L, OWNER_ID);

        PaymentObligationEntity o1 =
                obligation(1001L, 101L, 10000, ObligationStatus.ACTIVE);
        PaymentObligationEntity o2 =
                obligation(1002L, 101L, 20000, ObligationStatus.ACTIVE);

        PaymentRecordEntity r1 = paymentRecord(1001L, 10000);
        PaymentRecordEntity r2 = paymentRecord(1002L, 10000);

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
                paymentStatusService
                        .areAllObligationsResolved(
                                SETTLEMENT_ID
                        );

        assertThat(result)
                .isFalse();
    }

    @Test
    @DisplayName("납부의무가 하나도 없으면 해결 상태가 아니다")
    void areAllObligationsResolvedReturnsFalseWhenObligationsAreEmpty() {
        SettlementParticipant participant = participant(101L, OWNER_ID);

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
                paymentStatusService
                        .areAllObligationsResolved(
                                SETTLEMENT_ID
                        );

        assertThat(result)
                .isFalse();
    }

    private Settlement createSettlement() {
        return Settlement.builder()
                .settlementId(SETTLEMENT_ID)
                .owner(
                        User.builder().userId(OWNER_ID).build()
                )
                .build();
    }

    private SettlementParticipant participant(
            Long participantId,
            Long userId
    ) {
        SettlementParticipant participant =
                mock(SettlementParticipant.class);

        User user =
                mock(User.class);

        lenient().when(participant.getParticipantId())
                .thenReturn(participantId);
        lenient().when(participant.getUser())
                .thenReturn(user);
        lenient().when(user.getUserId())
                .thenReturn(userId);
        lenient().when(user.getName())
                .thenReturn("사용자" + userId);

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

        lenient().when(obligation.getPaymentObligationId())
                .thenReturn(obligationId);
        lenient().when(obligation.getParticipantId())
                .thenReturn(participantId);
        lenient().when(obligation.getExpectedAmount())
                .thenReturn(BigDecimal.valueOf(expectedAmount));
        lenient().when(obligation.getObligationStatus())
                .thenReturn(obligationStatus);

        return obligation;
    }

    private PaymentRecordEntity paymentRecord(
            Long obligationId,
            long amount
    ) {
        PaymentRecordEntity paymentRecord =
                mock(PaymentRecordEntity.class);

        lenient().when(paymentRecord.getTargetId())
                .thenReturn(obligationId);
        lenient().when(paymentRecord.getAmount())
                .thenReturn(BigDecimal.valueOf(amount));
        lenient().when(paymentRecord.getRecordedAt())
                .thenReturn(LocalDateTime.of(
                        2026,
                        8,
                        19,
                        12,
                        0
                ));

        return paymentRecord;
    }
}
