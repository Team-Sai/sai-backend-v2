package org.teamsai.saibackend.domain.payment.service;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        List<PaymentRecord> records =
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
        List<PaymentRecord> result =
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
        List<PaymentRecord> result =
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
        List<PaymentRecord> result =
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

        PaymentRecord savedRecord =
                org.mockito.Mockito.mock(
                        PaymentRecord.class
                );

        given(
                savedRecord.getPaymentRecordId()
        ).willReturn(1L);

        given(
                paymentRecordRepository.save(
                        any(PaymentRecord.class)
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
                .save(
                        any(PaymentRecord.class)
                );
        verify(paymentRecordRepository, never()).flush();
        verify(paymentRecordRepository, never()).saveAndFlush(any(PaymentRecord.class));
    }

    @Test
    @DisplayName("중복 검증을 마친 거래는 존재 조회 없이 확정 납부 기록을 저장한다")
    void createConfirmedRecordAfterDuplicateCheckSkipsExistenceQuery() {
        Long bankTransactionId = 100L;
        PaymentRecord savedRecord = org.mockito.Mockito.mock(PaymentRecord.class);
        given(savedRecord.getPaymentRecordId()).willReturn(1L);
        given(paymentRecordRepository.save(any(PaymentRecord.class)))
                .willReturn(savedRecord);

        Long result = paymentRecordService.createConfirmedRecordAfterDuplicateCheck(
                bankTransactionId,
                PaymentTargetType.SETTLEMENT,
                1L,
                new BigDecimal("10000"),
                SourceType.AUTO_MATCH
        );

        assertThat(result).isEqualTo(1L);
        verify(paymentRecordRepository, never())
                .existsByBankTransactionId(bankTransactionId);
        verify(paymentRecordRepository)
                .save(any(PaymentRecord.class));
        verify(paymentRecordRepository, never()).flush();
        verify(paymentRecordRepository, never()).saveAndFlush(any(PaymentRecord.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("두 저장 진입점 모두 save의 유니크 제약 예외를 중복 납부 오류로 변환한다")
    void saveMapsUniqueConstraintViolation(boolean duplicateAlreadyChecked) {
        ConstraintViolationException constraintViolation =
                new ConstraintViolationException(
                        "duplicate payment record",
                        new SQLException("duplicate key"),
                        "uk_payment_record_bank_transaction"
                );
        given(paymentRecordRepository.save(any(PaymentRecord.class)))
                .willThrow(new DataIntegrityViolationException(
                        "duplicate payment record",
                        constraintViolation
                ));

        assertThatThrownBy(() -> createThroughEntryPoint(duplicateAlreadyChecked))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.DUPLICATE_PAYMENT_RECORD)
                );
    }

    @Test
    @DisplayName("사전 조회에서 발견한 중복은 INSERT를 시도하지 않는다")
    void existingPaymentIsRejectedBeforeSave() {
        given(paymentRecordRepository.existsByBankTransactionId(100L)).willReturn(true);

        assertThatThrownBy(() -> createThroughEntryPoint(false))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.DUPLICATE_PAYMENT_RECORD));
        verify(paymentRecordRepository, never()).save(any(PaymentRecord.class));
        verify(paymentRecordRepository, never()).saveAndFlush(any(PaymentRecord.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("다른 제약 위반을 중복 납부로 바꾸지 않고 원래 예외로 전파한다")
    void savePreservesUnrelatedConstraintViolation(boolean duplicateAlreadyChecked) {
        var failure = new DataIntegrityViolationException("other constraint",
                new ConstraintViolationException("other constraint", new SQLException("check failed"),
                        "chk_payment_amount"));
        given(paymentRecordRepository.save(any(PaymentRecord.class))).willThrow(failure);

        assertThatThrownBy(() -> createThroughEntryPoint(duplicateAlreadyChecked)).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("제약 이름을 확인할 수 없는 무결성 오류는 중복 납부로 오인하지 않는다")
    void savePreservesUnclassifiedIntegrityViolation(boolean duplicateAlreadyChecked) {
        var failure = new DataIntegrityViolationException("unclassified integrity failure");
        given(paymentRecordRepository.save(any(PaymentRecord.class))).willThrow(failure);

        assertThatThrownBy(() -> createThroughEntryPoint(duplicateAlreadyChecked)).isSameAs(failure);
    }

    // Repository mocks test service exception mapping, not real IDENTITY INSERT timing or rollback.
    private Long createThroughEntryPoint(boolean duplicateAlreadyChecked) {
        if (duplicateAlreadyChecked) {
            return paymentRecordService.createConfirmedRecordAfterDuplicateCheck(
                    100L, PaymentTargetType.SETTLEMENT, 1L,
                    new BigDecimal("10000"), SourceType.AUTO_MATCH);
        }
        return paymentRecordService.createConfirmedRecord(
                100L, PaymentTargetType.LOAN, 1L,
                new BigDecimal("10000"), SourceType.MANUAL);
    }

    private PaymentRecord createRecord(
            Long bankTransactionId,
            Long targetId,
            String amount
    ) {
        return new PaymentRecord(
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
