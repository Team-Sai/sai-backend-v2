package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentData;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentReader;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementQueryService 납부 조회 단위 테스트")
class SettlementQueryServicePaymentTest {

    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_ID = 20L;
    private static final Long OTHER_USER_ID = 30L;
    private static final Long SETTLEMENT_ID = 15L;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @Mock
    private SettlementValidator settlementValidator;

    @Mock
    private BankTransactionService bankTransactionService;

    @Mock
    private SettlementPaymentReader settlementPaymentReader;

    @InjectMocks
    private SettlementQueryService settlementQueryService;

    @Test
    @DisplayName("납부의무별 금액을 합산하고 진행률을 계산한다")
    void getPaymentStatusCalculatesTotalsAndProgressRate() {
        Settlement settlement = paymentSettlement();

        SettlementParticipant p1 =
                participant(
                        101L,
                        OWNER_ID,
                        "채권자"
                );

        SettlementParticipant p2 =
                participant(
                        102L,
                        MEMBER_ID,
                        "참여자"
                );

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
                        102L,
                        20000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity r1 =
                paymentRecord(
                        1001L,
                        5000
                );

        PaymentRecordEntity r2 =
                paymentRecord(
                        1002L,
                        20000
                );

        SettlementPaymentData paymentData =
                paymentData(
                        List.of(p1, p2),
                        List.of(o1, o2),
                        List.of(r1, r2)
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );

        SettlementPaymentStatusResponse response =
                settlementQueryService.getPaymentStatus(
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
        Settlement settlement =
                paymentSettlement();

        SettlementParticipant participant =
                participant(
                        101L,
                        OWNER_ID,
                        "채권자"
                );

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
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o3 =
                obligation(
                        1003L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o4 =
                obligation(
                        1004L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity r1 =
                paymentRecord(
                        1001L,
                        10000
                );

        PaymentRecordEntity r2 =
                paymentRecord(
                        1002L,
                        4000
                );

        PaymentRecordEntity r3 =
                paymentRecord(
                        1003L,
                        4000
                );

        SettlementPaymentData paymentData =
                paymentData(
                        List.of(participant),
                        List.of(o1, o2, o3, o4),
                        List.of(r1, r2, r3)
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );

        SettlementPaymentStatusResponse response =
                settlementQueryService.getPaymentStatus(
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
    @DisplayName("모든 납부의무가 완납되면 마감 가능 상태가 된다")
    void getPaymentStatusIsClosableWhenAllObligationsArePaid() {
        Settlement settlement =
                paymentSettlement();

        SettlementParticipant participant =
                participant(
                        101L,
                        OWNER_ID,
                        "채권자"
                );

        PaymentObligationEntity obligation =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity record =
                paymentRecord(
                        1001L,
                        10000
                );

        SettlementPaymentData paymentData =
                paymentData(
                        List.of(participant),
                        List.of(obligation),
                        List.of(record)
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );

        SettlementPaymentStatusResponse response =
                settlementQueryService.getPaymentStatus(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.isClosable())
                .isTrue();

        assertThat(response.getProgressRate())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("초과 납부가 있어도 진행률은 100을 넘지 않고 마감할 수 없다")
    void getPaymentStatusIsNotClosableWhenPaymentExceedsExpectedAmount() {
        Settlement settlement =
                paymentSettlement();

        SettlementParticipant participant =
                participant(
                        101L,
                        OWNER_ID,
                        "채권자"
                );

        PaymentObligationEntity obligation =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity record =
                paymentRecord(
                        1001L,
                        11000
                );

        SettlementPaymentData paymentData =
                paymentData(
                        List.of(participant),
                        List.of(obligation),
                        List.of(record)
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );

        SettlementPaymentStatusResponse response =
                settlementQueryService.getPaymentStatus(
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
    @DisplayName("정산이 없으면 납부 현황 조회에 실패한다")
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
                        settlementQueryService.getPaymentStatus(
                                SETTLEMENT_ID,
                                OWNER_ID
                        )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode.SETTLEMENT_NOT_FOUND
                        )
        );

        verify(
                settlementValidator,
                never()
        ).validateAccessibleUser(
                any(),
                any()
        );

        verifyNoInteractions(
                settlementPaymentReader
        );
    }

    @Test
    @DisplayName("ACTIVE 정산 참여자는 납부 현황을 조회할 수 있다")
    void getPaymentStatusSucceedsForMember() {
        Settlement settlement =
                paymentSettlement();

        SettlementParticipant participant =
                participant(
                        101L,
                        MEMBER_ID,
                        "참여자"
                );

        PaymentObligationEntity obligation =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity record =
                paymentRecord(
                        1001L,
                        5000
                );

        SettlementPaymentData paymentData =
                paymentData(
                        List.of(participant),
                        List.of(obligation),
                        List.of(record)
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );

        SettlementPaymentStatusResponse response =
                settlementQueryService.getPaymentStatus(
                        SETTLEMENT_ID,
                        MEMBER_ID
                );

        assertThat(response).isNotNull();

        assertThat(response.getTotalExpectedAmount())
                .isEqualByComparingTo("10000");

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        MEMBER_ID
                );
    }

    @Test
    @DisplayName("정산과 관계없는 사용자는 납부 현황을 조회할 수 없다")
    void getPaymentStatusFailsWhenUserHasNoAccess() {
        Settlement settlement =
                paymentSettlement();

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
        ).when(
                settlementValidator
        ).validateAccessibleUser(
                settlement,
                OTHER_USER_ID
        );

        assertThatThrownBy(
                () ->
                        settlementQueryService.getPaymentStatus(
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

        verifyNoInteractions(
                settlementPaymentReader
        );
    }

    @Test
    @DisplayName("납부의무의 참여자 이름과 거래정보를 조합해 납부 내역을 만든다")
    void getPaymentHistoryComposesPayerNameAndTransactionInfo() {
        SettlementParticipant participant =
                participant(
                        101L,
                        OWNER_ID,
                        "홍길동"
                );

        PaymentObligationEntity obligation =
                obligation(
                        100L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity record =
                historyPaymentRecord(
                        100L,
                        200L,
                        "10000"
                );

        preparePaymentHistoryStatus(
                List.of(participant),
                List.of(obligation),
                List.of(record)
        );

        given(
                bankTransactionService.findById(
                        200L
                )
        ).willReturn(
                Optional.of(
                        bankTransaction(
                                200L,
                                "카카오뱅크 홍길동",
                                "TX-EXTERNAL-1"
                        )
                )
        );

        List<SettlementPaymentHistoryResponse> result =
                settlementQueryService.getPaymentHistory(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(result).hasSize(1);

        SettlementPaymentHistoryResponse history =
                result.get(0);

        assertThat(history.payerName())
                .isEqualTo("홍길동");

        assertThat(history.amount())
                .isEqualByComparingTo("10000");

        assertThat(history.sourceType())
                .isEqualTo(SourceType.AUTO_MATCH);

        assertThat(history.bankTransactionId())
                .isEqualTo(200L);

        assertThat(history.counterpartyName())
                .isEqualTo("카카오뱅크 홍길동");

        assertThat(history.externalTransactionId())
                .isEqualTo("TX-EXTERNAL-1");
    }

    @Test
    @DisplayName("연결된 은행거래를 찾을 수 없으면 거래정보는 null이다")
    void getPaymentHistoryFillsNullWhenBankTransactionNotFound() {
        SettlementParticipant participant =
                participant(
                        101L,
                        OWNER_ID,
                        "홍길동"
                );

        PaymentObligationEntity obligation =
                obligation(
                        100L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentRecordEntity record =
                historyPaymentRecord(
                        100L,
                        999L,
                        "10000"
                );

        preparePaymentHistoryStatus(
                List.of(participant),
                List.of(obligation),
                List.of(record)
        );

        given(
                bankTransactionService.findById(
                        999L
                )
        ).willReturn(
                Optional.empty()
        );

        List<SettlementPaymentHistoryResponse> result =
                settlementQueryService.getPaymentHistory(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(result).hasSize(1);

        SettlementPaymentHistoryResponse history =
                result.get(0);

        assertThat(history.payerName())
                .isEqualTo("홍길동");

        assertThat(history.counterpartyName())
                .isNull();

        assertThat(history.externalTransactionId())
                .isNull();
    }

    @Test
    @DisplayName("납부 이력 조회에서도 접근 권한을 검증한다")
    void getPaymentHistoryValidatesAccess() {
        Settlement settlement =
                paymentSettlement();

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                SettlementPaymentData.empty()
        );

        settlementQueryService.getPaymentHistory(
                SETTLEMENT_ID,
                OWNER_ID
        );

        verify(settlementValidator)
                .validateAccessibleUser(
                        settlement,
                        OWNER_ID
                );
    }

    private void preparePaymentHistoryStatus(
            List<SettlementParticipant> participants,
            List<PaymentObligationEntity> obligations,
            List<PaymentRecordEntity> paymentRecords
    ) {
        Settlement settlement =
                paymentSettlement();

        SettlementPaymentData paymentData =
                paymentData(
                        participants,
                        obligations,
                        paymentRecords
                );

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                paymentData
        );
    }

    private SettlementPaymentData paymentData(
            List<SettlementParticipant> participants,
            List<PaymentObligationEntity> obligations,
            List<PaymentRecordEntity> paymentRecords
    ) {
        Map<Long, BigDecimal> paidAmountMap =
                paymentRecords.stream()
                        .collect(Collectors.groupingBy(
                                PaymentRecordEntity::getTargetId,
                                Collectors.mapping(
                                        PaymentRecordEntity::getAmount,
                                        Collectors.reducing(
                                                BigDecimal.ZERO,
                                                BigDecimal::add
                                        )
                                )
                        ));

        return new SettlementPaymentData(
                participants,
                obligations,
                paymentRecords,
                paidAmountMap
        );
    }

    private Settlement paymentSettlement() {
        return Settlement.builder()
                .settlementId(
                        SETTLEMENT_ID
                )
                .owner(
                        User.builder()
                                .userId(
                                        OWNER_ID
                                )
                                .build()
                )
                .build();
    }

    private SettlementParticipant participant(
            Long participantId,
            Long userId,
            String name
    ) {
        SettlementParticipant participant =
                mock(
                        SettlementParticipant.class
                );

        User user =
                mock(
                        User.class
                );

        lenient()
                .when(
                        participant.getParticipantId()
                )
                .thenReturn(
                        participantId
                );

        lenient()
                .when(
                        participant.getUser()
                )
                .thenReturn(
                        user
                );

        lenient()
                .when(
                        user.getUserId()
                )
                .thenReturn(
                        userId
                );

        lenient()
                .when(
                        user.getName()
                )
                .thenReturn(
                        name
                );

        return participant;
    }

    private PaymentObligationEntity obligation(
            Long obligationId,
            Long participantId,
            long expectedAmount,
            ObligationStatus obligationStatus
    ) {
        PaymentObligationEntity obligation =
                mock(
                        PaymentObligationEntity.class
                );

        lenient()
                .when(
                        obligation.getPaymentObligationId()
                )
                .thenReturn(
                        obligationId
                );

        lenient()
                .when(
                        obligation.getParticipantId()
                )
                .thenReturn(
                        participantId
                );

        lenient()
                .when(
                        obligation.getExpectedAmount()
                )
                .thenReturn(
                        BigDecimal.valueOf(
                                expectedAmount
                        )
                );

        lenient()
                .when(
                        obligation.getObligationStatus()
                )
                .thenReturn(
                        obligationStatus
                );

        return obligation;
    }

    private PaymentRecordEntity paymentRecord(
            Long obligationId,
            long amount
    ) {
        PaymentRecordEntity paymentRecord =
                mock(
                        PaymentRecordEntity.class
                );

        lenient()
                .when(
                        paymentRecord.getTargetId()
                )
                .thenReturn(
                        obligationId
                );

        lenient()
                .when(
                        paymentRecord.getAmount()
                )
                .thenReturn(
                        BigDecimal.valueOf(
                                amount
                        )
                );

        lenient()
                .when(
                        paymentRecord.getRecordedAt()
                )
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                8,
                                19,
                                12,
                                0
                        )
                );

        return paymentRecord;
    }

    private PaymentRecordEntity historyPaymentRecord(
            Long targetId,
            Long bankTransactionId,
            String amount
    ) {
        return new PaymentRecordEntity(
                bankTransactionId,
                PaymentTargetType.SETTLEMENT,
                targetId,
                new BigDecimal(
                        amount
                ),
                SourceType.AUTO_MATCH,
                RecordStatus.CONFIRMED,
                LocalDateTime.of(
                        2026,
                        8,
                        18,
                        12,
                        0
                )
        );
    }

    private BankTransactionEntity bankTransaction(
            Long bankTransactionId,
            String counterpartyName,
            String externalTransactionId
    ) {
        BankTransactionEntity transaction =
                new BankTransactionEntity(
                        1L,
                        externalTransactionId,
                        BigDecimal.ONE,
                        BankTransactionType.DEPOSIT,
                        LocalDateTime.of(
                                2026,
                                8,
                                5,
                                10,
                                0
                        ),
                        counterpartyName,
                        null,
                        LocalDateTime.of(
                                2026,
                                8,
                                5,
                                10,
                                1
                        )
                );

        ReflectionTestUtils.setField(
                transaction,
                "bankTransactionId",
                bankTransactionId
        );

        return transaction;
    }
}