package org.teamsai.saibackend.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRecordService 단위 테스트")
class PaymentRecordServiceTest {

    @Mock
    private PaymentRecordRepository paymentRecordRepository;

    @InjectMocks
    private PaymentRecordService paymentRecordService;

    @Test
    @DisplayName("특정 대상의 확정 납부 금액을 조회한다")
    void sumConfirmedAmountByTarget() {
        // given
        PaymentTargetType targetType =
                PaymentTargetType.SETTLEMENT;

        Long targetId = 1L;

        BigDecimal confirmedAmount =
                new BigDecimal("30000");

        given(
                paymentRecordRepository.sumConfirmedAmountByTarget(
                        targetType,
                        targetId,
                        RecordStatus.CONFIRMED
                )
        ).willReturn(confirmedAmount);

        // when
        BigDecimal result =
                paymentRecordService.sumConfirmedAmountByTarget(
                        targetType,
                        targetId
                );

        // then
        assertThat(result)
                .isEqualByComparingTo(confirmedAmount);

        verify(paymentRecordRepository)
                .sumConfirmedAmountByTarget(
                        targetType,
                        targetId,
                        RecordStatus.CONFIRMED
                );
    }

    @Test
    @DisplayName("대상 ID 목록으로 확정 납부 기록을 조회한다")
    void findConfirmedRecordsByTargetIds() {
        // given
        PaymentTargetType targetType =
                PaymentTargetType.SETTLEMENT;

        List<Long> targetIds =
                List.of(1L, 2L);

        List<PaymentRecordEntity> records =
                List.of(
                        createRecord(1L, 1L, "10000"),
                        createRecord(2L, 2L, "20000")
                );

        given(
                paymentRecordRepository.findConfirmedByTargetIds(
                        targetType,
                        targetIds,
                        RecordStatus.CONFIRMED
                )
        ).willReturn(records);

        // when
        List<PaymentRecordEntity> result =
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        targetType,
                        targetIds
                );

        // then
        assertThat(result)
                .hasSize(2)
                .isEqualTo(records);

        verify(paymentRecordRepository)
                .findConfirmedByTargetIds(
                        targetType,
                        targetIds,
                        RecordStatus.CONFIRMED
                );
    }

    @Test
    @DisplayName("targetIds가 비어 있으면 Repository를 호출하지 않고 빈 목록을 반환한다")
    void findConfirmedRecordsReturnsEmptyListWhenTargetIdsIsEmpty() {
        // when
        List<PaymentRecordEntity> result =
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        List.of()
                );

        // then
        assertThat(result)
                .isEmpty();

        verify(paymentRecordRepository, never())
                .findConfirmedByTargetIds(
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("targetIds가 null이면 Repository를 호출하지 않고 빈 목록을 반환한다")
    void findConfirmedRecordsReturnsEmptyListWhenTargetIdsIsNull() {
        // when
        List<PaymentRecordEntity> result =
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        null
                );

        // then
        assertThat(result)
                .isEmpty();

        verify(paymentRecordRepository, never())
                .findConfirmedByTargetIds(
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("은행 거래 ID로 납부 기록 존재 여부를 조회한다")
    void existsByBankTransactionId() {
        // given
        Long bankTransactionId = 100L;

        given(
                paymentRecordRepository.existsByBankTransactionId(
                        bankTransactionId
                )
        ).willReturn(true);

        // when
        boolean result =
                paymentRecordService.existsByBankTransactionId(
                        bankTransactionId
                );

        // then
        assertThat(result)
                .isTrue();

        verify(paymentRecordRepository)
                .existsByBankTransactionId(
                        bankTransactionId
                );
    }

    @Test
    @DisplayName("확정 납부 기록을 저장하고 생성된 ID를 반환한다")
    void createConfirmedRecord() {
        // given
        Long bankTransactionId = 100L;
        PaymentTargetType targetType =
                PaymentTargetType.SETTLEMENT;
        Long targetId = 1L;
        BigDecimal amount =
                new BigDecimal("10000");
        SourceType sourceType =
                SourceType.MANUAL;

        given(
                paymentRecordRepository.existsByBankTransactionId(
                        bankTransactionId
                )
        ).willReturn(false);

        PaymentRecordEntity savedRecord =
                org.mockito.Mockito.mock(
                        PaymentRecordEntity.class
                );

        given(
                savedRecord.getPaymentRecordId()
        ).willReturn(1L);

        given(
                paymentRecordRepository.saveAndFlush(
                        any(PaymentRecordEntity.class)
                )
        ).willReturn(savedRecord);

        // when
        Long result =
                paymentRecordService.createConfirmedRecord(
                        bankTransactionId,
                        targetType,
                        targetId,
                        amount,
                        sourceType
                );

        // then
        assertThat(result)
                .isEqualTo(1L);

        verify(paymentRecordRepository)
                .existsByBankTransactionId(
                        bankTransactionId
                );

        verify(paymentRecordRepository)
                .saveAndFlush(
                        any(PaymentRecordEntity.class)
                );
    }

    private PaymentRecordEntity createRecord(
            Long bankTransactionId,
            Long targetId,
            String amount
    ) {
        return new PaymentRecordEntity(
                bankTransactionId,
                PaymentTargetType.SETTLEMENT,
                targetId,
                new BigDecimal(amount),
                SourceType.MANUAL,
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
}