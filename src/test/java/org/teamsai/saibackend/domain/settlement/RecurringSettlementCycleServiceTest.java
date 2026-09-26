package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAccount;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAccountRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.*;
import org.teamsai.saibackend.domain.settlement.support.CycleGenerationResult;
import org.teamsai.saibackend.domain.settlement.type.CycleGenerationStatus;
import org.teamsai.saibackend.domain.settlement.support.SettlementAmountCalculator;
import org.teamsai.saibackend.domain.settlement.type.*;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringSettlementCycleServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementParticipantRepository participantRepository;

    @Mock
    private PaymentObligationRepository paymentObligationRepository;

    @Mock
    private SettlementPaymentService settlementPaymentService;

    @Mock
    private SettlementAmountCalculator settlementAmountCalculator;

    @Mock
    private SettlementAccountRepository settlementAccountRepository;

    @InjectMocks
    private RecurringSettlementCycleService sut;


    private RecurringSettlement recurring(SplitType splitType) {
        return recurring(
                splitType,
                BigDecimal.valueOf(300000)
        );
    }

    private RecurringSettlement recurring(
            SplitType splitType,
            BigDecimal totalAmount
    ) {
        return RecurringSettlement.builder()
                .recurringSettlementId(1L)
                .owner(user(100L))
                .settlementCategory("월세")
                .title("자취방 월세")
                .splitType(splitType)
                .totalAmount(totalAmount)
                .cycleRule(CycleRule.MONTHLY)
                .startDate(LocalDate.of(2026, 1, 31))
                .endDate(null)
                .build();
    }

    private Settlement previousSettlement() {
        return settlement(
                10L,
                LocalDate.of(2026, 1, 31)
        );
    }

    private Settlement settlement(
            Long settlementId,
            LocalDate cycleDate
    ) {
        return Settlement.builder()
                .settlementId(settlementId)
                .recurringSettlement(
                        RecurringSettlement.builder()
                                .recurringSettlementId(1L)
                                .build()
                )
                .cycleDate(cycleDate)
                .build();
    }

    private SettlementParticipant activeParticipant(
            Long participantId,
            Long userId
    ) {
        return SettlementParticipant.builder()
                .participantId(participantId)
                .user(user(userId))
                .settlement(previousSettlement())
                .participantRole(SettlementParticipantRole.MEMBER)
                .participantStatus(SettlementParticipantStatus.ACTIVE)
                .build();
    }

    private User user(Long userId) {
        return User.builder()
                .userId(userId)
                .build();
    }

    private PaymentObligationEntity obligation(
            Long participantId,
            BigDecimal expectedAmount
    ) {
        return new PaymentObligationEntity(
                participantId,
                expectedAmount
        );
    }

    private List<ObligationStatus> allObligationStatuses() {
        return List.of(
                ObligationStatus.ACTIVE,
                ObligationStatus.EXCLUDED,
                ObligationStatus.CANCELLED,
                ObligationStatus.WRITTEN_OFF
        );
    }

    private void givenNoPreviousAccount() {
        lenient()
                .when(
                        settlementAccountRepository
                                .findBySettlementIdAndStatus(
                                        10L,
                                        SettlementAccountStatus.ACTIVE
                                )
                )
                .thenReturn(Optional.empty());
    }

    /**
     * Settlement 저장은 DB 대신 전달받은 Entity를 그대로 반환하도록 한다.
     */
    private void givenSettlementSaveSucceeds() {
        lenient()
                .when(
                        settlementRepository.save(
                                any(Settlement.class)
                        )
                )
                .thenAnswer(
                        invocation -> invocation.getArgument(0)
                );
    }

    /**
     * Participant 저장 후 생성 ID가 있다고 가정한다.
     */
    private void givenParticipantSaveSucceeds() {
        lenient()
                .when(
                        participantRepository.save(
                                any(SettlementParticipant.class)
                        )
                )
                .thenAnswer(invocation -> {
                    SettlementParticipant participant =
                            invocation.getArgument(0);

                    return SettlementParticipant.builder()
                            .participantId(999L)
                            .user(participant.getUser())
                            .settlement(participant.getSettlement())
                            .participantRole(
                                    participant.getParticipantRole()
                            )
                            .participantStatus(
                                    participant.getParticipantStatus()
                            )
                            .joinedAt(participant.getJoinedAt())
                            .build();
                });
    }

    private void givenJpaSavesSucceed() {
        givenSettlementSaveSucceeds();
        givenParticipantSaveSucceeds();
    }


    @Nested
    @DisplayName("동시성 락 검증")
    class LockValidation {

        @Test
        @DisplayName(
                "락 획득 시점의 직전 회차가 넘겨받은 previousSettlement와 다르면 "
                        + "CONCURRENTLY_SKIPPED를 반환하고 아무것도 생성하지 않는다"
        )
        void returnsConcurrentlySkippedWhenConcurrentGenerationDetected() {

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            Settlement alreadyCreatedByOther =
                    settlement(
                            11L,
                            LocalDate.of(2026, 2, 28)
                    );

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(alreadyCreatedByOther)
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(
                            CycleGenerationStatus.CONCURRENTLY_SKIPPED
                    );

            assertThat(outcome.settlement())
                    .isNull();

            verify(
                    settlementRepository,
                    never()
            ).save(any(Settlement.class));

            verify(
                    participantRepository,
                    never()
            ).findBySettlementIdAndStatus(
                    anyLong(),
                    any()
            );
        }


        @Test
        @DisplayName(
                "락 획득 시점의 직전 회차가 null이면 "
                        + "CONCURRENTLY_SKIPPED를 반환한다"
        )
        void returnsConcurrentlySkippedWhenLockedLatestIsNull() {

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of()
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(
                            CycleGenerationStatus.CONCURRENTLY_SKIPPED
                    );

            verify(
                    settlementRepository,
                    never()
            ).save(any(Settlement.class));
        }
    }


    @Nested
    @DisplayName("ACTIVE 참여자 존재 여부")
    class ActiveParticipantCheck {

        @Test
        @DisplayName(
                "ACTIVE 참여자가 없으면 "
                        + "NO_ACTIVE_PARTICIPANT를 반환하고 생성하지 않는다"
        )
        void returnsNoActiveParticipantWhenNoneExist() {

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of()
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(
                            CycleGenerationStatus.NO_ACTIVE_PARTICIPANT
                    );

            assertThat(outcome.settlement())
                    .isNull();

            verify(
                    settlementRepository,
                    never()
            ).save(any(Settlement.class));
        }
    }


    @Nested
    @DisplayName("EQUAL 분할 재계산")
    class EqualSplitRecalculation {

        @Test
        @DisplayName(
                "EQUAL이면 현재 ACTIVE 참여자 수 기준으로 금액을 재계산한다"
        )
        void recalculatesEqualAmountByCurrentActiveCount() {

            givenNoPreviousAccount();
            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L)
                    )
            );

            when(
                    settlementAmountCalculator
                            .calculateEqualAmount(
                                    BigDecimal.valueOf(300000),
                                    1
                            )
            ).thenReturn(
                    BigDecimal.valueOf(150000)
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(CycleGenerationStatus.CREATED);

            assertThat(outcome.settlement())
                    .isNotNull();

            verify(settlementAmountCalculator)
                    .calculateEqualAmount(
                            BigDecimal.valueOf(300000),
                            1
                    );

            verify(settlementPaymentService)
                    .createObligation(
                            any(),
                            eq(BigDecimal.valueOf(150000))
                    );

            verify(
                    paymentObligationRepository,
                    never()
            ).findLatestByParticipantIdsAndObligationStatuses(any(), any());
        }


        @Test
        @DisplayName(
                "EQUAL - 참여자가 여러 명이면 계산된 동일 금액이 모든 참여자에게 배정된다"
        )
        void assignsSameCalculatedAmountToEveryParticipant() {

            givenNoPreviousAccount();
            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(
                            SplitType.EQUAL,
                            BigDecimal.valueOf(10000)
                    );

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L),
                            activeParticipant(2L, 200L),
                            activeParticipant(3L, 300L)
                    )
            );

            when(
                    settlementAmountCalculator
                            .calculateEqualAmount(
                                    BigDecimal.valueOf(10000),
                                    3
                            )
            ).thenReturn(
                    BigDecimal.valueOf(3333)
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(CycleGenerationStatus.CREATED);

            verify(settlementAmountCalculator)
                    .calculateEqualAmount(
                            BigDecimal.valueOf(10000),
                            3
                    );

            verify(
                    settlementPaymentService,
                    times(3)
            ).createObligation(
                    any(),
                    eq(BigDecimal.valueOf(3333))
            );
        }


        @Test
        @DisplayName(
                "EQUAL이 아니면 참여자ID 목록으로 한 번 조회한 뒤 "
                        + "직전 회차 expectedAmount를 유지한다"
        )
        void keepsPreviousAmountWhenNotEqual() {

            givenNoPreviousAccount();
            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.CUSTOM);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L)
                    )
            );

            when(
                    paymentObligationRepository
                            .findLatestByParticipantIdsAndObligationStatuses(
                                    List.of(1L),
                                    allObligationStatuses()
                            )
            ).thenReturn(
                    List.of(
                            obligation(
                                    1L,
                                    BigDecimal.valueOf(150000)
                            )
                    )
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(CycleGenerationStatus.CREATED);

            verify(settlementPaymentService)
                    .createObligation(
                            any(),
                            eq(BigDecimal.valueOf(150000))
                    );

            verify(
                    settlementAmountCalculator,
                    never()
            ).calculateEqualAmount(
                    any(),
                    anyInt()
            );

            verify(
                    paymentObligationRepository,
                    times(1)
            ).findLatestByParticipantIdsAndObligationStatuses(any(), any());
        }


        @Test
        @DisplayName(
                "CUSTOM인데 직전 회차 납부의무가 없으면 "
                        + "PAYMENT_OBLIGATION_NOT_FOUND 예외를 던진다"
        )
        void throwsWhenCustomAndPreviousObligationMissing() {

            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.CUSTOM);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L)
                    )
            );

            when(
                    paymentObligationRepository
                            .findLatestByParticipantIdsAndObligationStatuses(
                                    List.of(1L),
                                    allObligationStatuses()
                            )
            ).thenReturn(
                    List.of()
            );

            assertThatThrownBy(
                    () -> sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    )
            )
                    .isInstanceOf(DomainException.class)
                    .extracting(
                            e -> ((DomainException) e)
                                    .getErrorCode()
                    )
                    .isEqualTo(
                            PaymentErrorCode.PAYMENT_OBLIGATION_NOT_FOUND
                    );
        }
    }


    @Nested
    @DisplayName("정상 생성 시 참여자 복사")
    class HappyPath {

        @Test
        @DisplayName(
                "ACTIVE 참여자만 복사하고 CREATED 결과와 생성된 Settlement를 반환한다"
        )
        void copiesOnlyActiveParticipantsWithCalculatedAmount() {

            givenNoPreviousAccount();
            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L),
                            activeParticipant(2L, 200L)
                    )
            );

            when(
                    settlementAmountCalculator
                            .calculateEqualAmount(
                                    BigDecimal.valueOf(300000),
                                    2
                            )
            ).thenReturn(
                    BigDecimal.valueOf(150000)
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(CycleGenerationStatus.CREATED);

            assertThat(outcome.settlement())
                    .isNotNull();

            assertThat(outcome.settlement().getCycleDate())
                    .isEqualTo(LocalDate.of(2026, 2, 28));

            verify(
                    participantRepository,
                    times(2)
            ).save(any(SettlementParticipant.class));

            verify(
                    settlementPaymentService,
                    times(2)
            ).createObligation(
                    any(),
                    eq(BigDecimal.valueOf(150000))
            );
        }


        @Test
        @DisplayName(
                "CUSTOM 참여자 복수 명에 대해 납부의무 배치 조회가 1번만 호출된다"
        )
        void batchQueriesObligationsOnceForMultipleParticipants() {

            givenNoPreviousAccount();
            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.CUSTOM);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L),
                            activeParticipant(2L, 200L),
                            activeParticipant(3L, 300L)
                    )
            );

            when(
                    paymentObligationRepository
                            .findLatestByParticipantIdsAndObligationStatuses(
                                    List.of(1L, 2L, 3L),
                                    allObligationStatuses()
                            )
            ).thenReturn(
                    List.of(
                            obligation(
                                    1L,
                                    BigDecimal.valueOf(100000)
                            ),
                            obligation(
                                    2L,
                                    BigDecimal.valueOf(120000)
                            ),
                            obligation(
                                    3L,
                                    BigDecimal.valueOf(80000)
                            )
                    )
            );

            CycleGenerationResult outcome =
                    sut.generateOneCycle(
                            recurring,
                            previous,
                            LocalDate.of(2026, 2, 28)
                    );

            assertThat(outcome.result())
                    .isEqualTo(CycleGenerationStatus.CREATED);

            verify(
                    participantRepository,
                    times(3)
            ).save(any(SettlementParticipant.class));

            verify(
                    paymentObligationRepository,
                    times(1)
            ).findLatestByParticipantIdsAndObligationStatuses(any(), any());

            verify(settlementPaymentService)
                    .createObligation(
                            any(),
                            eq(BigDecimal.valueOf(100000))
                    );

            verify(settlementPaymentService)
                    .createObligation(
                            any(),
                            eq(BigDecimal.valueOf(120000))
                    );

            verify(settlementPaymentService)
                    .createObligation(
                            any(),
                            eq(BigDecimal.valueOf(80000))
                    );
        }


        @Test
        @DisplayName(
                "직전 회차에 연결된 계좌가 있으면 새 회차에도 동일 계좌가 승계된다"
        )
        void copiesLinkedAccountFromPreviousSettlement() {

            givenJpaSavesSucceed();

            RecurringSettlement recurring =
                    recurring(SplitType.EQUAL);

            Settlement previous =
                    previousSettlement();

            when(
                    settlementRepository
                            .findLatestByRecurringIdForUpdate(
                                    eq(1L),
                                    any(Pageable.class)
                            )
            ).thenReturn(
                    List.of(previous)
            );

            when(
                    participantRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementParticipantStatus.ACTIVE
                            )
            ).thenReturn(
                    List.of(
                            activeParticipant(1L, 100L)
                    )
            );

            when(
                    settlementAmountCalculator
                            .calculateEqualAmount(
                                    BigDecimal.valueOf(300000),
                                    1
                            )
            ).thenReturn(
                    BigDecimal.valueOf(300000)
            );

            SettlementAccount existingAccount =
                    SettlementAccount.create(
                            previous,
                            500L,
                            LocalDateTime.now()
                    );

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatus(
                                    10L,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.of(existingAccount)
            );

            sut.generateOneCycle(
                    recurring,
                    previous,
                    LocalDate.of(2026, 2, 28)
            );

            verify(settlementAccountRepository)
                    .saveAndFlush(
                            argThat(account ->
                                    account.getLinkedAccountId()
                                            .equals(500L)
                                            && account.getAccountStatus()
                                            == SettlementAccountStatus.ACTIVE
                            )
                    );
        }
    }


    @Nested
    @DisplayName("회차 목표일 계산 (월말/윤년 앵커링)")
    class NthCycleDateCalculation {

        @Test
        @DisplayName(
                "MONTHLY - 1/31 시작, 1번째 회차는 2월 말일(28일)로 클램프된다"
        )
        void monthlyClampsToLastDayOfShortMonth() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2026, 1, 31),
                            CycleRule.MONTHLY,
                            1
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName(
                "MONTHLY - 2번째 회차는 anchor인 31일로 복귀한다"
        )
        void monthlyAnchorRecoversOnNextMonth() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2026, 1, 31),
                            CycleRule.MONTHLY,
                            2
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName(
                "YEARLY - 2/29 시작, 평년엔 2/28로 클램프된다"
        )
        void yearlyClampsToFeb28InNonLeapYear() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2024, 2, 29),
                            CycleRule.YEARLY,
                            1
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2025, 2, 28));
        }

        @Test
        @DisplayName(
                "YEARLY - 다음 윤년에는 anchor인 2/29로 복귀한다"
        )
        void yearlyAnchorRecoversOnNextLeapYear() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2024, 2, 29),
                            CycleRule.YEARLY,
                            4
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2028, 2, 29));
        }

        @Test
        @DisplayName(
                "WEEKLY - n주 뒤 날짜를 정확히 계산한다"
        )
        void weeklyAddsExactWeeksFromStartDate() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2026, 1, 5),
                            CycleRule.WEEKLY,
                            3
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2026, 1, 26));
        }

        @Test
        @DisplayName(
                "DAILY - n일 뒤 날짜를 정확히 계산한다"
        )
        void dailyAddsExactDaysFromStartDate() {

            LocalDate result =
                    sut.calculateNthCycleDate(
                            LocalDate.of(2026, 1, 1),
                            CycleRule.DAILY,
                            5
                    );

            assertThat(result)
                    .isEqualTo(LocalDate.of(2026, 1, 6));
        }
    }
}
