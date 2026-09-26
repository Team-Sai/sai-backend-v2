package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementParticipantService;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantRole;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementParticipantService 단위 테스트")
class SettlementParticipantServiceTest {

    private static final Long SETTLEMENT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long PARTICIPANT_ID = 100L;

    private static final Long OWNER_ID = 1L;

    private static final Long FIRST_USER_ID = 101L;
    private static final Long SECOND_USER_ID = 102L;

    private static final Long FIRST_PARTICIPANT_ID = 1001L;
    private static final Long SECOND_PARTICIPANT_ID = 1002L;

    private static final String FIRST_USER_TOKEN = "first-user-token";
    private static final String SECOND_USER_TOKEN = "second-user-token";

    private static final BigDecimal EXPECTED_AMOUNT =
            new BigDecimal("50000");

    @Mock
    private SettlementParticipantRepository participantRepository;

    @Mock
    private SettlementRepository settlementRepository;


    @Mock
    private UserService userService;

    @Mock
    private SettlementPaymentService settlementPaymentService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private SettlementParticipantService participantService;


    @Test
    @DisplayName(
            "정산 ID와 사용자 ID로 ACTIVE MEMBER 참여자를 생성하고 참여자 ID를 반환한다"
    )
    void createParticipantSuccess() {

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .build();

        User user =
                User.builder()
                        .userId(USER_ID)
                        .build();

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                userService.getUser(
                        USER_ID
                )
        ).willReturn(user);

        given(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).willAnswer(invocation -> {

            SettlementParticipant participant =
                    invocation.getArgument(0);

            return SettlementParticipant.builder()
                    .participantId(PARTICIPANT_ID)
                    .settlement(participant.getSettlement())
                    .user(participant.getUser())
                    .participantRole(participant.getParticipantRole())
                    .participantStatus(participant.getParticipantStatus())
                    .joinedAt(participant.getJoinedAt())
                    .build();
        });


        Long result =
                participantService.createParticipant(
                        SETTLEMENT_ID,
                        USER_ID
                );


        ArgumentCaptor<SettlementParticipant> captor =
                ArgumentCaptor.forClass(
                        SettlementParticipant.class
                );

        verify(participantRepository)
                .save(captor.capture());

        SettlementParticipant savedParticipant =
                captor.getValue();


        assertThat(result)
                .isEqualTo(PARTICIPANT_ID);

        assertThat(
                savedParticipant.getSettlement()
                        .getSettlementId()
        ).isEqualTo(SETTLEMENT_ID);

        assertThat(
                savedParticipant.getUser()
                        .getUserId()
        ).isEqualTo(USER_ID);

        assertThat(savedParticipant.getParticipantRole())
                .isEqualTo(
                        SettlementParticipantRole.MEMBER
                );

        assertThat(savedParticipant.getParticipantStatus())
                .isEqualTo(
                        SettlementParticipantStatus.ACTIVE
                );

        assertThat(savedParticipant.getJoinedAt())
                .isNotNull();
    }


    @Test
    @DisplayName(
            "참여자 저장 중 예외가 발생하면 예외를 그대로 전달한다"
    )
    void createParticipantFailsWhenSaveFails() {

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .build();

        User user =
                User.builder()
                        .userId(USER_ID)
                        .build();

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                userService.getUser(
                        USER_ID
                )
        ).willReturn(user);

        given(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).willThrow(
                new RuntimeException("저장 실패")
        );


        assertThatThrownBy(
                () ->
                        participantService.createParticipant(
                                SETTLEMENT_ID,
                                USER_ID
                        )
        ).isInstanceOf(
                RuntimeException.class
        );


        verify(participantRepository)
                .save(
                        any(SettlementParticipant.class)
                );
    }


    @Test
    @DisplayName(
            "존재하지 않는 정산이면 SETTLEMENT_NOT_FOUND 예외가 발생한다"
    )
    void createParticipantFailsWhenSettlementNotFound() {

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.empty()
        );


        assertThatThrownBy(
                () ->
                        participantService.createParticipant(
                                SETTLEMENT_ID,
                                USER_ID
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


        verify(userService, never())
                .getUser(any());

        verify(participantRepository, never())
                .save(any());
    }


    @Test
    @DisplayName(
            "존재하지 않는 사용자이면 USER_NOT_FOUND 예외가 발생한다"
    )
    void createParticipantFailsWhenUserNotFound() {

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .build();

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                userService.getUser(
                        USER_ID
                )
        ).willThrow(org.teamsai.saibackend.domain.user.exception.UserErrorCode.USER_NOT_FOUND.toException());


        assertThatThrownBy(
                () ->
                        participantService.createParticipant(
                                SETTLEMENT_ID,
                                USER_ID
                        )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                UserErrorCode.USER_NOT_FOUND
                        )
        );


        verify(participantRepository, never())
                .save(any());
    }


    @Test
    @DisplayName(
            "참여자를 등록하면 사용자 조회 후 참여자와 납부 의무를 생성하고 알림을 전송한다"
    )
    void registerParticipantsSuccess() {

        List<CreateSettlementParticipantRequest> participants =
                List.of(
                        participantRequest(FIRST_USER_TOKEN),
                        participantRequest(SECOND_USER_TOKEN)
                );

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .build();

        User firstUser =
                User.builder()
                        .userId(FIRST_USER_ID)
                        .userToken(FIRST_USER_TOKEN)
                        .build();

        User secondUser =
                User.builder()
                        .userId(SECOND_USER_ID)
                        .userToken(SECOND_USER_TOKEN)
                        .build();


        given(
                userService.findRequestTarget(
                        OWNER_ID,
                        FIRST_USER_TOKEN
                )
        ).willReturn(firstUser);

        given(
                userService.findRequestTarget(
                        OWNER_ID,
                        SECOND_USER_TOKEN
                )
        ).willReturn(secondUser);

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                userService.getUser(
                        FIRST_USER_ID
                )
        ).willReturn(firstUser);

        given(
                userService.getUser(
                        SECOND_USER_ID
                )
        ).willReturn(secondUser);

        given(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).willAnswer(invocation -> {

            SettlementParticipant participant =
                    invocation.getArgument(0);

            Long savedParticipantId =
                    participant.getUser()
                            .getUserId()
                            .equals(FIRST_USER_ID)
                            ? FIRST_PARTICIPANT_ID
                            : SECOND_PARTICIPANT_ID;

            return SettlementParticipant.builder()
                    .participantId(savedParticipantId)
                    .settlement(participant.getSettlement())
                    .user(participant.getUser())
                    .participantRole(participant.getParticipantRole())
                    .participantStatus(participant.getParticipantStatus())
                    .joinedAt(participant.getJoinedAt())
                    .build();
        });


        participantService.registerParticipants(
                OWNER_ID,
                SETTLEMENT_ID,
                participants,
                EXPECTED_AMOUNT
        );


        verify(userService)
                .findRequestTarget(
                        OWNER_ID,
                        FIRST_USER_TOKEN
                );

        verify(userService)
                .findRequestTarget(
                        OWNER_ID,
                        SECOND_USER_TOKEN
                );

        verify(settlementRepository, times(2))
                .findById(
                        SETTLEMENT_ID
                );

        verify(userService)
                .getUser(
                        FIRST_USER_ID
                );

        verify(userService)
                .getUser(
                        SECOND_USER_ID
                );

        verify(participantRepository, times(2))
                .save(
                        any(SettlementParticipant.class)
                );

        verify(settlementPaymentService)
                .createObligation(
                        FIRST_PARTICIPANT_ID,
                        EXPECTED_AMOUNT
                );

        verify(settlementPaymentService)
                .createObligation(
                        SECOND_PARTICIPANT_ID,
                        EXPECTED_AMOUNT
                );

        verify(notificationService)
                .create(
                        FIRST_USER_ID,
                        NotificationType.SETTLEMENT_PARTICIPANT_ADDED,
                        "새로운 정산에 참여자로 등록되었습니다.",
                        "정산 금액 "
                                + EXPECTED_AMOUNT.toPlainString()
                                + "원이 등록되었습니다.",
                        SETTLEMENT_ID
                );

        verify(notificationService)
                .create(
                        SECOND_USER_ID,
                        NotificationType.SETTLEMENT_PARTICIPANT_ADDED,
                        "새로운 정산에 참여자로 등록되었습니다.",
                        "정산 금액 "
                                + EXPECTED_AMOUNT.toPlainString()
                                + "원이 등록되었습니다.",
                        SETTLEMENT_ID
                );
    }


    @Test
    @DisplayName(
            "참여자 등록은 사용자 조회, 참여자 저장, 납부 의무 생성, 알림 순서로 처리한다"
    )
    void registerParticipantInOrder() {

        List<CreateSettlementParticipantRequest> participants =
                List.of(
                        participantRequest(FIRST_USER_TOKEN)
                );

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .build();

        User participantUser =
                User.builder()
                        .userId(FIRST_USER_ID)
                        .userToken(FIRST_USER_TOKEN)
                        .build();


        given(
                userService.findRequestTarget(
                        OWNER_ID,
                        FIRST_USER_TOKEN
                )
        ).willReturn(participantUser);

        given(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                userService.getUser(
                        FIRST_USER_ID
                )
        ).willReturn(participantUser);

        given(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).willAnswer(invocation -> {

            SettlementParticipant participant =
                    invocation.getArgument(0);

            return SettlementParticipant.builder()
                    .participantId(FIRST_PARTICIPANT_ID)
                    .settlement(participant.getSettlement())
                    .user(participant.getUser())
                    .participantRole(participant.getParticipantRole())
                    .participantStatus(participant.getParticipantStatus())
                    .joinedAt(participant.getJoinedAt())
                    .build();
        });


        participantService.registerParticipants(
                OWNER_ID,
                SETTLEMENT_ID,
                participants,
                EXPECTED_AMOUNT
        );


        InOrder inOrder =
                inOrder(
                        userService,
                        participantRepository,
                        settlementPaymentService,
                        notificationService
                );


        inOrder.verify(userService)
                .findRequestTarget(
                        OWNER_ID,
                        FIRST_USER_TOKEN
                );

        inOrder.verify(participantRepository)
                .save(
                        any(SettlementParticipant.class)
                );

        inOrder.verify(settlementPaymentService)
                .createObligation(
                        FIRST_PARTICIPANT_ID,
                        EXPECTED_AMOUNT
                );

        inOrder.verify(notificationService)
                .create(
                        FIRST_USER_ID,
                        NotificationType.SETTLEMENT_PARTICIPANT_ADDED,
                        "새로운 정산에 참여자로 등록되었습니다.",
                        "정산 금액 "
                                + EXPECTED_AMOUNT.toPlainString()
                                + "원이 등록되었습니다.",
                        SETTLEMENT_ID
                );
    }


    private CreateSettlementParticipantRequest participantRequest(
            String userToken
    ) {
        CreateSettlementParticipantRequest request =
                mock(CreateSettlementParticipantRequest.class);

        given(
                request.getUserToken()
        ).willReturn(userToken);

        return request;
    }
}