package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantRole;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SettlementParticipantService {

    private final SettlementParticipantRepository settlementParticipantRepository;
    private final SettlementRepository settlementRepository;
    private final UserRepository userRepository;
    @Transactional
    public Long createParticipant(
            Long settlementId,
            Long userId
    ) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(
                        SettlementErrorCode.SETTLEMENT_NOT_FOUND::toException
                );

        User user = userRepository.findById(userId)
                .orElseThrow(
                        UserErrorCode.USER_NOT_FOUND::toException
                );

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
}
