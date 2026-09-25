package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateRecurringSettlementRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateRecurringSettlementResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.RecurringSettlementRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.*;
import org.teamsai.saibackend.domain.settlement.support.RecurringSettlementValidator;
import org.teamsai.saibackend.domain.settlement.support.SettlementAmountCalculator;
import org.teamsai.saibackend.domain.settlement.type.CycleRule;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecurringSettlementService 단위 테스트")
class RecurringSettlementServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long RECURRING_SETTLEMENT_ID = 10L;
    private static final Long FIRST_SETTLEMENT_ID = 100L;
    private static final Long LINKED_ACCOUNT_ID = 5L;

    @Mock
    private RecurringSettlementRepository recurringSettlementRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RecurringSettlementValidator recurringSettlementValidator;

    @Mock
    private SettlementAmountCalculator settlementAmountCalculator;

    @Mock
    private SettlementParticipantService participantService;

    @Mock
    private SettlementAccountService settlementAccountService;

    @InjectMocks
    private RecurringSettlementService recurringSettlementService;

    @Test
    @DisplayName("정기정산 생성 시 정기 설정과 최초 회차를 생성한다")
    void createRecurringSettlementSuccess() {
        CreateRecurringSettlementRequest request = createRequest();

        BigDecimal perPersonAmount = new BigDecimal("150000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(userRepository.findById(OWNER_ID))
                .willReturn(Optional.of(owner));

        given(settlementAmountCalculator.calculateEqualAmount(
                request.getTotalAmount(),
                request.getParticipants().size()
        )).willReturn(perPersonAmount);

        given(recurringSettlementRepository.save(
                any(RecurringSettlement.class)
        )).willAnswer(invocation -> {
            RecurringSettlement recurring = invocation.getArgument(0);

            return RecurringSettlement.builder()
                    .recurringSettlementId(RECURRING_SETTLEMENT_ID)
                    .owner(recurring.getOwner())
                    .settlementCategory(recurring.getSettlementCategory())
                    .title(recurring.getTitle())
                    .splitType(recurring.getSplitType())
                    .totalAmount(recurring.getTotalAmount())
                    .cycleRule(recurring.getCycleRule())
                    .startDate(recurring.getStartDate())
                    .endDate(recurring.getEndDate())
                    .createdAt(recurring.getCreatedAt())
                    .build();
        });

        given(settlementRepository.save(
                any(Settlement.class)
        )).willAnswer(invocation -> {
            Settlement settlement = invocation.getArgument(0);

            return Settlement.builder()
                    .settlementId(FIRST_SETTLEMENT_ID)
                    .recurringSettlement(settlement.getRecurringSettlement())
                    .owner(settlement.getOwner())
                    .settlementType(settlement.getSettlementType())
                    .settlementStatus(settlement.getSettlementStatus())
                    .settlementCategory(settlement.getSettlementCategory())
                    .title(settlement.getTitle())
                    .splitType(settlement.getSplitType())
                    .totalAmount(settlement.getTotalAmount())
                    .dueDate(settlement.getDueDate())
                    .cycleDate(settlement.getCycleDate())
                    .createdAt(settlement.getCreatedAt())
                    .build();
        });

        CreateRecurringSettlementResponse response =
                recurringSettlementService.create(
                        OWNER_ID,
                        request
                );

        ArgumentCaptor<RecurringSettlement> recurringCaptor =
                ArgumentCaptor.forClass(RecurringSettlement.class);

        ArgumentCaptor<Settlement> settlementCaptor =
                ArgumentCaptor.forClass(Settlement.class);

        then(recurringSettlementValidator)
                .should()
                .validateCreateRequest(request);

        then(settlementAmountCalculator)
                .should()
                .calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                );

        then(recurringSettlementRepository)
                .should()
                .save(recurringCaptor.capture());

        RecurringSettlement savedRecurring =
                recurringCaptor.getValue();

        assertThat(savedRecurring.getOwner().getUserId())
                .isEqualTo(OWNER_ID);

        assertThat(savedRecurring.getSettlementCategory())
                .isEqualTo("OTT·구독");

        assertThat(savedRecurring.getTitle())
                .isEqualTo("넷플릭스 구독");

        assertThat(savedRecurring.getSplitType())
                .isEqualTo(SplitType.EQUAL);

        assertThat(savedRecurring.getTotalAmount())
                .isEqualByComparingTo("450000");

        assertThat(savedRecurring.getCycleRule())
                .isEqualTo(CycleRule.MONTHLY);

        assertThat(savedRecurring.getStartDate())
                .isEqualTo(LocalDate.of(2026, 8, 17));

        assertThat(savedRecurring.getEndDate())
                .isEqualTo(LocalDate.of(2027, 8, 17));

        then(settlementRepository)
                .should()
                .save(
                        settlementCaptor.capture()
                );

        Settlement firstSettlement =
                settlementCaptor.getValue();

        assertThat(firstSettlement.getRecurringSettlement()
                .getRecurringSettlementId())
                .isEqualTo(RECURRING_SETTLEMENT_ID);

        assertThat(firstSettlement.getOwner().getUserId())
                .isEqualTo(OWNER_ID);

        assertThat(firstSettlement.getSettlementType())
                .isEqualTo(SettlementType.RECURRING);

        assertThat(firstSettlement.getSettlementStatus())
                .isEqualTo(SettlementStatus.IN_PROGRESS);

        assertThat(firstSettlement.getSettlementCategory())
                .isEqualTo("OTT·구독");

        assertThat(firstSettlement.getTitle())
                .isEqualTo("넷플릭스 구독");

        assertThat(firstSettlement.getSplitType())
                .isEqualTo(SplitType.EQUAL);

        assertThat(firstSettlement.getTotalAmount())
                .isEqualByComparingTo("450000");

        assertThat(firstSettlement.getCycleDate())
                .isEqualTo(LocalDate.of(2026, 8, 17));

        assertThat(firstSettlement.getDueDate())
                .isNull();

        then(participantService)
                .should()
                .registerParticipants(
                        OWNER_ID,
                        FIRST_SETTLEMENT_ID,
                        request.getParticipants(),
                        perPersonAmount
                );

        then(settlementAccountService)
                .should()
                .selectAccount(
                        OWNER_ID,
                        FIRST_SETTLEMENT_ID,
                        LINKED_ACCOUNT_ID
                );

        assertThat(response.getRecurringSettlementId())
                .isEqualTo(RECURRING_SETTLEMENT_ID);

        assertThat(response.getFirstSettlementId())
                .isEqualTo(FIRST_SETTLEMENT_ID);

        assertThat(response.getSettlementType())
                .isEqualTo(SettlementType.RECURRING);

        assertThat(response.getCycleRule())
                .isEqualTo(CycleRule.MONTHLY);

        assertThat(response.getStartDate())
                .isEqualTo(LocalDate.of(2026, 8, 17));

        assertThat(response.getEndDate())
                .isEqualTo(LocalDate.of(2027, 8, 17));
    }

    @Test
    @DisplayName("정기정산 설정 저장에 실패하면 최초 회차를 생성하지 않는다")
    void createFailsWhenRecurringSettlementInsertFails() {
        CreateRecurringSettlementRequest request =
                createRequest();

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(userRepository.findById(OWNER_ID))
                .willReturn(Optional.of(owner));

        given(settlementAmountCalculator.calculateEqualAmount(
                request.getTotalAmount(),
                request.getParticipants().size()
        )).willReturn(
                new BigDecimal("150000")
        );

        given(recurringSettlementRepository.save(
                any(RecurringSettlement.class)
        )).willThrow(
                new RuntimeException("저장 실패")
        );

        assertThatThrownBy(() ->
                recurringSettlementService.create(
                        OWNER_ID,
                        request
                )
        ).isInstanceOf(
                RuntimeException.class
        );

        verifyNoInteractions(
                settlementRepository,
                participantService,
                settlementAccountService
        );
    }

    @Test
    @DisplayName("최초 회차 생성에 실패하면 참여자와 수취 계좌를 생성하지 않는다")
    void createFailsWhenFirstSettlementInsertFails() {
        CreateRecurringSettlementRequest request =
                createRequest();

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(userRepository.findById(OWNER_ID))
                .willReturn(Optional.of(owner));

        given(settlementAmountCalculator.calculateEqualAmount(
                request.getTotalAmount(),
                request.getParticipants().size()
        )).willReturn(
                new BigDecimal("150000")
        );

        given(recurringSettlementRepository.save(
                any(RecurringSettlement.class)
        )).willAnswer(invocation -> {
            RecurringSettlement recurring =
                    invocation.getArgument(0);

            return RecurringSettlement.builder()
                    .recurringSettlementId(RECURRING_SETTLEMENT_ID)
                    .owner(recurring.getOwner())
                    .settlementCategory(recurring.getSettlementCategory())
                    .title(recurring.getTitle())
                    .splitType(recurring.getSplitType())
                    .totalAmount(recurring.getTotalAmount())
                    .cycleRule(recurring.getCycleRule())
                    .startDate(recurring.getStartDate())
                    .endDate(recurring.getEndDate())
                    .createdAt(recurring.getCreatedAt())
                    .build();
        });

        given(settlementRepository.save(
                any(Settlement.class)
        )).willThrow(
                new RuntimeException("저장 실패")
        );

        assertThatThrownBy(() ->
                recurringSettlementService.create(
                        OWNER_ID,
                        request
                )
        ).isInstanceOf(
                RuntimeException.class
        );

        verifyNoInteractions(
                participantService,
                settlementAccountService
        );
    }

    @Test
    @DisplayName("정기정산 요청 검증에 실패하면 어떤 데이터도 생성하지 않는다")
    void createFailsWhenValidationFails() {
        CreateRecurringSettlementRequest request =
                createRequest();

        willThrow(
                SettlementErrorCode
                        .DUPLICATE_SETTLEMENT_PARTICIPANT
                        .toException()
        )
                .given(recurringSettlementValidator)
                .validateCreateRequest(request);

        assertThatThrownBy(() ->
                recurringSettlementService.create(
                        OWNER_ID,
                        request
                )
        ).isInstanceOf(DomainException.class);

        verifyNoInteractions(
                settlementAmountCalculator,
                recurringSettlementRepository,
                settlementRepository,
                userRepository,
                participantService,
                settlementAccountService
        );
    }

    @Test
    @DisplayName("참여자 등록 시 공동정산과 동일한 균등 분배 금액을 전달한다")
    void usesSharedSettlementAmountCalculationPolicy() {
        CreateRecurringSettlementRequest request =
                createRequest();

        BigDecimal expectedAmount =
                new BigDecimal("150000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(userRepository.findById(OWNER_ID))
                .willReturn(Optional.of(owner));

        given(settlementAmountCalculator.calculateEqualAmount(
                new BigDecimal("450000"),
                2
        )).willReturn(expectedAmount);

        given(recurringSettlementRepository.save(
                any(RecurringSettlement.class)
        )).willAnswer(invocation -> {
            RecurringSettlement recurring =
                    invocation.getArgument(0);

            return RecurringSettlement.builder()
                    .recurringSettlementId(RECURRING_SETTLEMENT_ID)
                    .owner(recurring.getOwner())
                    .settlementCategory(recurring.getSettlementCategory())
                    .title(recurring.getTitle())
                    .splitType(recurring.getSplitType())
                    .totalAmount(recurring.getTotalAmount())
                    .cycleRule(recurring.getCycleRule())
                    .startDate(recurring.getStartDate())
                    .endDate(recurring.getEndDate())
                    .createdAt(recurring.getCreatedAt())
                    .build();
        });

        given(settlementRepository.save(
                any(Settlement.class)
        )).willAnswer(invocation -> {
            Settlement settlement =
                    invocation.getArgument(0);

            return Settlement.builder()
                    .settlementId(FIRST_SETTLEMENT_ID)
                    .recurringSettlement(settlement.getRecurringSettlement())
                    .owner(settlement.getOwner())
                    .settlementType(settlement.getSettlementType())
                    .settlementStatus(settlement.getSettlementStatus())
                    .settlementCategory(settlement.getSettlementCategory())
                    .title(settlement.getTitle())
                    .splitType(settlement.getSplitType())
                    .totalAmount(settlement.getTotalAmount())
                    .dueDate(settlement.getDueDate())
                    .cycleDate(settlement.getCycleDate())
                    .createdAt(settlement.getCreatedAt())
                    .build();
        });

        recurringSettlementService.create(
                OWNER_ID,
                request
        );

        then(settlementAmountCalculator)
                .should()
                .calculateEqualAmount(
                        new BigDecimal("450000"),
                        2
                );

        then(participantService)
                .should()
                .registerParticipants(
                        OWNER_ID,
                        FIRST_SETTLEMENT_ID,
                        request.getParticipants(),
                        expectedAmount
                );
    }

    @Test
    @DisplayName("수취 계좌 설정 실패 시 예외를 그대로 전달한다")
    void createFailsWhenSettlementAccountCreationFails() {
        CreateRecurringSettlementRequest request =
                createRequest();

        BigDecimal perPersonAmount =
                new BigDecimal("150000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(userRepository.findById(OWNER_ID))
                .willReturn(Optional.of(owner));

        given(settlementAmountCalculator.calculateEqualAmount(
                request.getTotalAmount(),
                request.getParticipants().size()
        )).willReturn(perPersonAmount);

        given(recurringSettlementRepository.save(
                any(RecurringSettlement.class)
        )).willAnswer(invocation -> {
            RecurringSettlement recurring =
                    invocation.getArgument(0);

            return RecurringSettlement.builder()
                    .recurringSettlementId(RECURRING_SETTLEMENT_ID)
                    .owner(recurring.getOwner())
                    .settlementCategory(recurring.getSettlementCategory())
                    .title(recurring.getTitle())
                    .splitType(recurring.getSplitType())
                    .totalAmount(recurring.getTotalAmount())
                    .cycleRule(recurring.getCycleRule())
                    .startDate(recurring.getStartDate())
                    .endDate(recurring.getEndDate())
                    .createdAt(recurring.getCreatedAt())
                    .build();
        });

        given(settlementRepository.save(
                any(Settlement.class)
        )).willAnswer(invocation -> {
            Settlement settlement =
                    invocation.getArgument(0);

            return Settlement.builder()
                    .settlementId(FIRST_SETTLEMENT_ID)
                    .recurringSettlement(settlement.getRecurringSettlement())
                    .owner(settlement.getOwner())
                    .settlementType(settlement.getSettlementType())
                    .settlementStatus(settlement.getSettlementStatus())
                    .settlementCategory(settlement.getSettlementCategory())
                    .title(settlement.getTitle())
                    .splitType(settlement.getSplitType())
                    .totalAmount(settlement.getTotalAmount())
                    .dueDate(settlement.getDueDate())
                    .cycleDate(settlement.getCycleDate())
                    .createdAt(settlement.getCreatedAt())
                    .build();
        });

        given(settlementAccountService.selectAccount(
                OWNER_ID,
                FIRST_SETTLEMENT_ID,
                LINKED_ACCOUNT_ID
        )).willThrow(
                SettlementErrorCode
                        .SETTLEMENT_ACCOUNT_CREATE_FAILED
                        .toException()
        );

        assertThatThrownBy(() ->
                recurringSettlementService.create(
                        OWNER_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        SettlementErrorCode.SETTLEMENT_ACCOUNT_CREATE_FAILED
                                )
        );

        then(participantService)
                .should()
                .registerParticipants(
                        OWNER_ID,
                        FIRST_SETTLEMENT_ID,
                        request.getParticipants(),
                        perPersonAmount
                );
    }

    private CreateRecurringSettlementRequest createRequest() {
        return CreateRecurringSettlementRequest.builder()
                .settlementCategory("OTT·구독")
                .title("넷플릭스 구독")
                .totalAmount(new BigDecimal("450000"))
                .cycleRule(CycleRule.MONTHLY)
                .startDate(LocalDate.of(2026, 8, 17))
                .endDate(LocalDate.of(2027, 8, 17))
                .linkedAccountId(LINKED_ACCOUNT_ID)
                .participants(List.of(
                        participant("SAI_USER_A"),
                        participant("SAI_USER_B")
                ))
                .build();
    }

    private CreateSettlementParticipantRequest participant(
            String userToken
    ) {
        return CreateSettlementParticipantRequest.builder()
                .userToken(userToken)
                .build();
    }
}