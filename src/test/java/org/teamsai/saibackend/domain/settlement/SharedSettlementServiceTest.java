package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSharedSettlementRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateSharedSettlementResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.*;
import org.teamsai.saibackend.domain.settlement.support.SettlementAmountCalculator;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("SharedSettlementService 단위 테스트")
class SharedSettlementServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long SETTLEMENT_ID = 15L;
    private static final Long LINKED_ACCOUNT_ID = 10L;


    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SettlementAccountService settlementAccountService;

    @Mock
    private SettlementValidator settlementValidator;

    @Mock
    private SettlementParticipantService participantService;

    @Mock
    private SettlementAmountCalculator settlementAmountCalculator;

    @InjectMocks
    private SharedSettlementService sharedSettlementService;


    @Test
    @DisplayName(
            "공동정산 생성 시 정산을 저장하고 참여자 등록과 수취 계좌 설정을 요청한다"
    )
    void createSharedSettlementSuccess() {

        CreateSharedSettlementRequest request =
                createRequest(
                        new BigDecimal("450000"),
                        List.of(
                                participant("SAI_USER_A"),
                                participant("SAI_USER_B")
                        )
                );

        BigDecimal expectedAmount =
                new BigDecimal("150000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(
                userRepository.findById(OWNER_ID)
        ).willReturn(
                Optional.of(owner)
        );


        given(
                settlementAmountCalculator.calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                )
        ).willReturn(
                expectedAmount
        );


        given(
                settlementRepository.save(
                        any(Settlement.class)
                )
        ).willAnswer(invocation -> {

            Settlement settlement =
                    invocation.getArgument(0);

            return Settlement.builder()
                    .settlementId(SETTLEMENT_ID)
                    .owner(settlement.getOwner())
                    .settlementType(settlement.getSettlementType())
                    .settlementStatus(settlement.getSettlementStatus())
                    .settlementCategory(settlement.getSettlementCategory())
                    .title(settlement.getTitle())
                    .splitType(settlement.getSplitType())
                    .totalAmount(settlement.getTotalAmount())
                    .dueDate(settlement.getDueDate())
                    .createdAt(settlement.getCreatedAt())
                    .build();
        });


        CreateSharedSettlementResponse response =
                sharedSettlementService.create(
                        OWNER_ID,
                        request
                );


        ArgumentCaptor<Settlement> settlementCaptor =
                ArgumentCaptor.forClass(
                        Settlement.class
                );


        verify(settlementRepository)
                .save(
                        settlementCaptor.capture()
                );


        Settlement savedSettlement =
                settlementCaptor.getValue();


        assertThat(savedSettlement.getOwner().getUserId())
                .isEqualTo(OWNER_ID);

        assertThat(savedSettlement.getSettlementType())
                .isEqualTo(
                        SettlementType.SHARED
                );

        assertThat(savedSettlement.getSettlementStatus())
                .isEqualTo(
                        SettlementStatus.IN_PROGRESS
                );

        assertThat(savedSettlement.getSettlementCategory())
                .isEqualTo("여행");

        assertThat(savedSettlement.getTitle())
                .isEqualTo(
                        "제주도 여행비 정산"
                );

        assertThat(savedSettlement.getSplitType())
                .isEqualTo(
                        SplitType.EQUAL
                );

        assertThat(savedSettlement.getTotalAmount())
                .isEqualByComparingTo(
                        "450000"
                );

        assertThat(savedSettlement.getRecurringSettlement())
                .isNull();

        assertThat(savedSettlement.getCycleDate())
                .isNull();


        verify(settlementValidator)
                .validateCreateRequest(
                        request
                );


        verify(settlementAmountCalculator)
                .calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                );


        verify(participantService)
                .registerParticipants(
                        OWNER_ID,
                        SETTLEMENT_ID,
                        request.getParticipants(),
                        expectedAmount
                );


        verify(settlementAccountService)
                .selectAccount(
                        OWNER_ID,
                        SETTLEMENT_ID,
                        LINKED_ACCOUNT_ID
                );


        assertThat(response.getSettlementId())
                .isEqualTo(SETTLEMENT_ID);

        assertThat(response.getSettlementType())
                .isEqualTo(
                        SettlementType.SHARED
                );

        assertThat(response.getSettlementStatus())
                .isEqualTo(
                        SettlementStatus.IN_PROGRESS
                );

        assertThat(response.getTitle())
                .isEqualTo(
                        "제주도 여행비 정산"
                );
    }


    @Test
    @DisplayName(
            "공동정산 저장에 실패하면 참여자 등록과 수취 계좌 설정을 하지 않는다"
    )
    void createSharedSettlementFailsWhenSaveFails() {

        CreateSharedSettlementRequest request =
                createRequest(
                        new BigDecimal("30000"),
                        List.of(
                                participant(
                                        "SAI_USER_A"
                                )
                        )
                );

        BigDecimal expectedAmount =
                new BigDecimal("15000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(
                userRepository.findById(OWNER_ID)
        ).willReturn(
                Optional.of(owner)
        );


        given(
                settlementAmountCalculator.calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                )
        ).willReturn(
                expectedAmount
        );


        given(
                settlementRepository.save(
                        any(Settlement.class)
                )
        ).willThrow(
                new RuntimeException("저장 실패")
        );


        assertThatThrownBy(
                () ->
                        sharedSettlementService.create(
                                OWNER_ID,
                                request
                        )
        ).isInstanceOf(
                RuntimeException.class
        );


        verify(settlementValidator)
                .validateCreateRequest(
                        request
                );

        verify(settlementAmountCalculator)
                .calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                );

        verifyNoInteractions(
                participantService,
                settlementAccountService
        );
    }


    @Test
    @DisplayName(
            "수취 계좌 설정에 실패하면 정산 생성에 실패한다"
    )
    void failsWhenSettlementAccountSelectionFails() {

        CreateSharedSettlementRequest request =
                createRequest(
                        new BigDecimal("30000"),
                        List.of(
                                participant(
                                        "SAI_USER_A"
                                )
                        )
                );

        BigDecimal expectedAmount =
                new BigDecimal("15000");

        User owner =
                User.builder()
                        .userId(OWNER_ID)
                        .build();

        given(
                userRepository.findById(OWNER_ID)
        ).willReturn(
                Optional.of(owner)
        );


        given(
                settlementAmountCalculator.calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                )
        ).willReturn(
                expectedAmount
        );


        given(
                settlementRepository.save(
                        any(Settlement.class)
                )
        ).willAnswer(invocation -> {

            Settlement settlement =
                    invocation.getArgument(0);

            return Settlement.builder()
                    .settlementId(SETTLEMENT_ID)
                    .owner(settlement.getOwner())
                    .settlementType(settlement.getSettlementType())
                    .settlementStatus(settlement.getSettlementStatus())
                    .settlementCategory(settlement.getSettlementCategory())
                    .title(settlement.getTitle())
                    .splitType(settlement.getSplitType())
                    .totalAmount(settlement.getTotalAmount())
                    .dueDate(settlement.getDueDate())
                    .createdAt(settlement.getCreatedAt())
                    .build();
        });


        given(
                settlementAccountService
                        .selectAccount(
                                OWNER_ID,
                                SETTLEMENT_ID,
                                LINKED_ACCOUNT_ID
                        )
        ).willThrow(
                SettlementErrorCode
                        .SETTLEMENT_ACCOUNT_CREATE_FAILED
                        .toException()
        );


        assertThatThrownBy(
                () ->
                        sharedSettlementService.create(
                                OWNER_ID,
                                request
                        )
        ).isInstanceOf(
                DomainException.class
        );


        verify(participantService)
                .registerParticipants(
                        OWNER_ID,
                        SETTLEMENT_ID,
                        request.getParticipants(),
                        expectedAmount
                );


        verify(settlementAccountService)
                .selectAccount(
                        OWNER_ID,
                        SETTLEMENT_ID,
                        LINKED_ACCOUNT_ID
                );
    }


    private CreateSharedSettlementRequest createRequest(
            BigDecimal totalAmount,
            List<CreateSettlementParticipantRequest> participants
    ) {

        return CreateSharedSettlementRequest
                .builder()
                .settlementCategory(
                        "여행"
                )
                .title(
                        "제주도 여행비 정산"
                )
                .dueDate(
                        LocalDate.now()
                                .plusDays(7)
                )
                .totalAmount(
                        totalAmount
                )
                .linkedAccountId(
                        LINKED_ACCOUNT_ID
                )
                .participants(
                        participants
                )
                .build();
    }


    private CreateSettlementParticipantRequest participant(
            String userToken
    ) {

        return CreateSettlementParticipantRequest
                .builder()
                .userToken(
                        userToken
                )
                .build();
    }
}