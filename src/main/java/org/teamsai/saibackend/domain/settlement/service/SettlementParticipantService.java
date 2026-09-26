package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantRole;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.service.UserService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementParticipantService {

    private final SettlementParticipantRepository settlementParticipantRepository;
    private final SettlementRepository settlementRepository;
    private final UserService userService;
    private final SettlementPaymentService settlementPaymentService;
    private final NotificationService notificationService;

    @Transactional
    public Long createParticipant(
            Long settlementId,
            Long userId
    ) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(
                        SettlementErrorCode.SETTLEMENT_NOT_FOUND::toException
                );

        User user = userService.getUser(userId);

        SettlementParticipant participant =
                SettlementParticipant.builder()
                        .settlement(settlement)
                        .user(user)
                        .participantRole(SettlementParticipantRole.MEMBER)
                        .participantStatus(SettlementParticipantStatus.ACTIVE)
                        .joinedAt(LocalDateTime.now())
                        .build();

        SettlementParticipant savedParticipant =
                settlementParticipantRepository.save(participant);

        return savedParticipant.getParticipantId();
    }
    @Transactional
    public void registerParticipants(
            Long ownerId,
            Long settlementId,
            List<CreateSettlementParticipantRequest> participants,
            BigDecimal expectedAmount
    ) {
        for (CreateSettlementParticipantRequest participantRequest : participants) {

            User participantUser =
                    userService.findRequestTarget(
                            ownerId,
                            participantRequest.getUserToken()
                    );

            Long participantId =
                    createParticipant(
                            settlementId,
                            participantUser.getUserId()
                    );

            settlementPaymentService.createObligation(
                    participantId,
                    expectedAmount
            );

            notificationService.create(
                    participantUser.getUserId(),
                    NotificationType.SETTLEMENT_PARTICIPANT_ADDED,
                    "새로운 정산에 참여자로 등록되었습니다.",
                    "정산 금액 "
                            + expectedAmount.toPlainString()
                            + "원이 등록되었습니다.",
                    settlementId
            );
        }
    }
}
