package org.teamsai.saibackend.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.ReviewStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentService 단위 테스트")
class PaymentServiceTest {

    private static final Long PAYMENT_OBLIGATION_ID = 1L;
    private static final Long BANK_TRANSACTION_ID = 101L;
    private static final Long PARTICIPANT_ID = 11L;

    private static final BigDecimal EXPECTED_AMOUNT =
            new BigDecimal("150000");

    @Mock
    private PaymentObligationRepository paymentObligationRepository;

    @Mock
    private PaymentRecordService paymentRecordService;

    @InjectMocks
    private SettlementPaymentService paymentService;


    @Nested
    @DisplayName("납부 반영")
    class ApplyPayment {

        @Test
        @DisplayName(
                "남은 금액과 같은 금액을 납부하면 납부기록을 생성하고 완납 상태로 변경한다"
        )
        void applyPaymentFullyPaid() {
            PaymentObligationEntity obligation = createActiveObligation();
            LocalDateTime overdueSince = LocalDateTime.of(2026, 1, 1, 0, 0);
            obligation.markOverdue(overdueSince);

            BigDecimal amount =
                    new BigDecimal("70000");

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            obligation
                    )
            );

            given(
                    paymentRecordService
                            .sumConfirmedAmountByTarget(
                                    PaymentTargetType.SETTLEMENT,
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    new BigDecimal("30000")
            );



            paymentService.applyAutoMatchedPayment(
                    PAYMENT_OBLIGATION_ID,
                    BANK_TRANSACTION_ID,
                    amount
            );


            verify(paymentRecordService)
                    .createConfirmedRecord(
                            BANK_TRANSACTION_ID,
                            PaymentTargetType.SETTLEMENT,
                            PAYMENT_OBLIGATION_ID,
                            amount,
                            SourceType.AUTO_MATCH
                    );

            assertThat(obligation.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(obligation.getOverdueSince()).isNull();
        }


        @Test
        @DisplayName(
                "남은 금액보다 적은 금액을 납부하면 부분납 상태로 변경한다"
        )
        void applyPaymentPartiallyPaid() {
            PaymentObligationEntity obligation = createActiveObligation();
            LocalDateTime overdueSince = LocalDateTime.of(2026, 1, 1, 0, 0);
            obligation.markOverdue(overdueSince);

            BigDecimal amount =
                    new BigDecimal("50000");

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            obligation
                    )
            );

            given(
                    paymentRecordService
                            .sumConfirmedAmountByTarget(
                                    PaymentTargetType.SETTLEMENT,
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    new BigDecimal("30000")
            );



            paymentService.applyAutoMatchedPayment(
                    PAYMENT_OBLIGATION_ID,
                    BANK_TRANSACTION_ID,
                    amount
            );


            assertThat(obligation.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
            assertThat(obligation.getOverdueSince()).isEqualTo(overdueSince);
        }
    }


    @Nested
    @DisplayName("납부 반영 검증")
    class ValidateApplyPayment {

        @Test
        @DisplayName(
                "납부의무가 없으면 예외가 발생한다"
        )
        void applyPaymentFailsWhenObligationDoesNotExist() {

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.empty()
            );


            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            new BigDecimal("10000")
                                    ),
                    PaymentErrorCode
                            .PAYMENT_OBLIGATION_NOT_FOUND
            );


            verify(
                    paymentRecordService,
                    never()
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );
        }


        @Test
        @DisplayName(
                "활성 상태가 아닌 납부의무면 예외가 발생한다"
        )
        void applyPaymentFailsWhenObligationIsNotActive() {

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            createObligation(
                                    ObligationStatus.CANCELLED
                            )
                    )
            );


            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            new BigDecimal("10000")
                                    ),
                    PaymentErrorCode
                            .PAYMENT_OBLIGATION_NOT_ACTIVE
            );


            verify(
                    paymentRecordService,
                    never()
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );
        }


        @Test
        @DisplayName(
                "납부 금액이 0 이하이면 예외가 발생한다"
        )
        void applyPaymentFailsWhenAmountIsInvalid() {

            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            BigDecimal.ZERO
                                    ),
                    PaymentErrorCode
                            .INVALID_PAYMENT_AMOUNT
            );


            verify(
                    paymentRecordService,
                    never()
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );

            verify(
                    paymentObligationRepository,
                    never()
            ).findByIdForUpdate(
                    PAYMENT_OBLIGATION_ID
            );
        }


        @Test
        @DisplayName(
                "은행 거래 ID가 없으면 예외가 발생한다"
        )
        void applyPaymentFailsWhenBankTransactionIdIsNull() {

            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            null,
                                            new BigDecimal("10000")
                                    ),
                    PaymentErrorCode
                            .INVALID_BANK_TRANSACTION_ID
            );


            verify(
                    paymentRecordService,
                    never()
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );

            verify(
                    paymentObligationRepository,
                    never()
            ).findByIdForUpdate(
                    PAYMENT_OBLIGATION_ID
            );
        }


        @Test
        @DisplayName(
                "같은 은행 거래 ID로 이미 반영된 납부기록이 있으면 예외가 발생한다"
        )
        void applyPaymentFailsWhenBankTransactionIsAlreadyApplied() {
            given(paymentRecordService.existsByBankTransactionId(BANK_TRANSACTION_ID))
                    .willReturn(true);

            assertPaymentExceptionThrownBy(
                    () -> paymentService.applyAutoMatchedPayment(
                            PAYMENT_OBLIGATION_ID, BANK_TRANSACTION_ID, new BigDecimal("10000")),
                    PaymentErrorCode.DUPLICATE_PAYMENT_RECORD
            );

            verify(paymentObligationRepository, never()).findByIdForUpdate(any());
            verify(paymentRecordService, never())
                    .createConfirmedRecord(any(), any(), any(), any(), any());
        }


        @Test
        @DisplayName(
                "납부 금액이 남은 금액을 초과하면 예외가 발생한다"
        )
        void applyPaymentFailsWhenAmountExceedsRemainingAmount() {

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            createActiveObligation()
                    )
            );

            given(
                    paymentRecordService
                            .sumConfirmedAmountByTarget(
                                    PaymentTargetType.SETTLEMENT,
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    new BigDecimal("30000")
            );


            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            new BigDecimal("80000")
                                    ),
                    PaymentErrorCode
                            .PAYMENT_AMOUNT_EXCEEDS_REMAINING_AMOUNT
            );


            verify(
                    paymentRecordService,
                    never()
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );
        }


        @Test
        @DisplayName(
                "납부기록 생성에 실패하면 예외가 발생한다"
        )
        void applyPaymentFailsWhenPaymentRecordCreateFails() {
            PaymentObligationEntity obligation = createActiveObligation();

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            obligation
                    )
            );

            given(
                    paymentRecordService
                            .sumConfirmedAmountByTarget(
                                    PaymentTargetType.SETTLEMENT,
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    BigDecimal.ZERO
            );

            willThrow(
                    PaymentErrorCode
                            .PAYMENT_RECORD_CREATE_FAILED
                            .toException()
            ).given(
                    paymentRecordService
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );


            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            new BigDecimal("10000")
                                    ),
                    PaymentErrorCode
                            .PAYMENT_RECORD_CREATE_FAILED
            );


            assertThat(obligation.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        }


        @Test
        @DisplayName(
                "납부기록 서비스의 중복 예외를 전달하고 납부 상태를 변경하지 않는다"
        )
        void applyPaymentFailsWhenDuplicateKeyExceptionOccursOnInsert() {
            PaymentObligationEntity obligation = createActiveObligation();

            given(
                    paymentObligationRepository
                            .findByIdForUpdate(
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    Optional.of(
                            obligation
                    )
            );

            given(
                    paymentRecordService
                            .sumConfirmedAmountByTarget(
                                    PaymentTargetType.SETTLEMENT,
                                    PAYMENT_OBLIGATION_ID
                            )
            ).willReturn(
                    BigDecimal.ZERO
            );

            willThrow(
                    PaymentErrorCode
                            .DUPLICATE_PAYMENT_RECORD
                            .toException()
            ).given(
                    paymentRecordService
            ).createConfirmedRecord(
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );


            assertPaymentExceptionThrownBy(
                    () ->
                            paymentService
                                    .applyAutoMatchedPayment(
                                            PAYMENT_OBLIGATION_ID,
                                            BANK_TRANSACTION_ID,
                                            new BigDecimal("10000")
                                    ),
                    PaymentErrorCode
                            .DUPLICATE_PAYMENT_RECORD
            );


            assertThat(obligation.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        }


        @Test
        @DisplayName(
                "이미 완납된 납부의무에는 추가 납부를 반영하지 않는다"
        )
        void applyPaymentFailsWhenObligationIsAlreadyPaid() {
            PaymentObligationEntity obligation = createActiveObligation();
            obligation.changePaymentStatus(PaymentStatus.PAID);
            given(paymentObligationRepository.findByIdForUpdate(PAYMENT_OBLIGATION_ID))
                    .willReturn(Optional.of(obligation));

            assertPaymentExceptionThrownBy(
                    () -> paymentService.applyAutoMatchedPayment(
                            PAYMENT_OBLIGATION_ID, BANK_TRANSACTION_ID, new BigDecimal("10000")),
                    PaymentErrorCode.PAYMENT_OBLIGATION_NOT_ACTIVE
            );

            assertThat(obligation.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            verify(paymentRecordService, never())
                    .createConfirmedRecord(any(), any(), any(), any(), any());
        }


        @Nested
        @DisplayName("납부 의무 생성")
        class CreatePaymentObligation {

            @Test
            @DisplayName(
                    "참여자별 예정 원금과 초기 상태로 납부 의무를 생성한다"
            )
            void createObligationSucceeds() {

                given(
                        paymentObligationRepository.saveAndFlush(
                                any(
                                        PaymentObligationEntity.class
                                )
                        )
                ).willAnswer(invocation -> {
                    PaymentObligationEntity entity = invocation.getArgument(0);
                    ReflectionTestUtils.setField(entity, "paymentObligationId", PAYMENT_OBLIGATION_ID);
                    return entity;
                });


                Long createdId = paymentService.createObligation(
                        PARTICIPANT_ID,
                        EXPECTED_AMOUNT
                );


                assertThat(createdId).isEqualTo(PAYMENT_OBLIGATION_ID);

                ArgumentCaptor<PaymentObligationEntity> captor =
                        ArgumentCaptor.forClass(
                                PaymentObligationEntity.class
                        );

                verify(paymentObligationRepository)
                        .saveAndFlush(
                                captor.capture()
                        );


                PaymentObligationEntity savedObligation =
                        captor.getValue();


                assertThat(
                        savedObligation.getParticipantId()
                ).isEqualTo(
                        PARTICIPANT_ID
                );

                assertThat(
                        savedObligation.getExpectedAmount()
                ).isEqualByComparingTo(
                        EXPECTED_AMOUNT
                );

                assertThat(
                        savedObligation.getPaymentStatus()
                ).isEqualTo(
                        PaymentStatus.UNPAID
                );

                assertThat(
                        savedObligation.getReviewStatus()
                ).isEqualTo(
                        ReviewStatus.NORMAL
                );

                assertThat(
                        savedObligation.getObligationStatus()
                ).isEqualTo(
                        ObligationStatus.ACTIVE
                );
            }


            @Test
            @DisplayName(
                    "참여자 ID가 없으면 납부 의무를 생성하지 않는다"
            )
            void createObligationFailsWhenParticipantIdIsNull() {

                assertPaymentExceptionThrownBy(
                        () ->
                                paymentService
                                        .createObligation(
                                                null,
                                                EXPECTED_AMOUNT
                                        ),
                        PaymentErrorCode
                                .INVALID_PAYMENT_OBLIGATION_REQUEST
                );


                verify(
                        paymentObligationRepository,
                        never()
                ).saveAndFlush(
                        any(
                                PaymentObligationEntity.class
                        )
                );
            }


            @Test
            @DisplayName(
                    "예정 원금이 없으면 납부 의무를 생성하지 않는다"
            )
            void createObligationFailsWhenExpectedAmountIsNull() {

                assertPaymentExceptionThrownBy(
                        () ->
                                paymentService
                                        .createObligation(
                                                PARTICIPANT_ID,
                                                null
                                        ),
                        PaymentErrorCode
                                .INVALID_PAYMENT_OBLIGATION_REQUEST
                );


                verify(
                        paymentObligationRepository,
                        never()
                ).saveAndFlush(
                        any(
                                PaymentObligationEntity.class
                        )
                );
            }


            @Test
            @DisplayName(
                    "예정 원금이 0 이하이면 납부 의무를 생성하지 않는다"
            )
            void createObligationFailsWhenExpectedAmountIsNotPositive() {

                assertPaymentExceptionThrownBy(
                        () ->
                                paymentService
                                        .createObligation(
                                                PARTICIPANT_ID,
                                                BigDecimal.ZERO
                                        ),
                        PaymentErrorCode
                                .INVALID_PAYMENT_OBLIGATION_REQUEST
                );


                verify(
                        paymentObligationRepository,
                        never()
                ).saveAndFlush(
                        any(
                                PaymentObligationEntity.class
                        )
                );
            }


            @Test
            @DisplayName(
                    "납부 의무 저장 실패 시 저장 예외가 호출자에게 전달된다"
            )
            void createObligationPropagatesSaveFailure() {
                DataIntegrityViolationException failure =
                        new DataIntegrityViolationException("납부 의무 저장 실패");
                given(paymentObligationRepository.saveAndFlush(any(PaymentObligationEntity.class)))
                        .willThrow(failure);

                assertThatThrownBy(() -> paymentService.createObligation(PARTICIPANT_ID, EXPECTED_AMOUNT))
                        .isSameAs(failure);
            }
        }
    }


    private PaymentObligationEntity createActiveObligation() {
        return createObligation(
                ObligationStatus.ACTIVE
        );
    }


    private PaymentObligationEntity createObligation(
            ObligationStatus obligationStatus
    ) {

        PaymentObligationEntity obligation =
                new PaymentObligationEntity(1L, new BigDecimal("100000"));
        // DB에서 조회된 ID와 상태를 테스트용 Entity에 재현한다.
        ReflectionTestUtils.setField(obligation, "paymentObligationId", PAYMENT_OBLIGATION_ID);
        ReflectionTestUtils.setField(obligation, "obligationStatus", obligationStatus);
        return obligation;
    }


    private void assertPaymentExceptionThrownBy(
            Runnable operation,
            PaymentErrorCode errorCode
    ) {

        assertThatThrownBy(
                operation::run
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                errorCode
                        )
        );
    }
}
