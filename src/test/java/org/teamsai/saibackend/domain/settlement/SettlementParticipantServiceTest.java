package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementParticipantService 단위 테스트")
class SettlementParticipantServiceTest {

    private static final Long SETTLEMENT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long PARTICIPANT_ID = 100L;

    @Mock
    private SettlementParticipantRepository participantRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private UserRepository userRepository;

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

        when(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).thenReturn(
                Optional.of(settlement)
        );

        when(
                userRepository.findById(
                        USER_ID
                )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).thenAnswer(invocation -> {

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

        when(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).thenReturn(
                Optional.of(settlement)
        );

        when(
                userRepository.findById(
                        USER_ID
                )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                participantRepository.save(
                        any(SettlementParticipant.class)
                )
        ).thenThrow(
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

        when(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).thenReturn(
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


        verify(userRepository, never())
                .findById(any());

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

        when(
                settlementRepository.findById(
                        SETTLEMENT_ID
                )
        ).thenReturn(
                Optional.of(settlement)
        );

        when(
                userRepository.findById(
                        USER_ID
                )
        ).thenReturn(
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
                                UserErrorCode.USER_NOT_FOUND
                        )
        );


        verify(participantRepository, never())
                .save(any());
    }
}