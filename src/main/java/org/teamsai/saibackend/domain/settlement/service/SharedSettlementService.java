package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSharedSettlementRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateSharedSettlementResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SharedSettlementService {

    private final SettlementAccountService settlementAccountService;
    private final SettlementValidator settlementValidator;
    private final SettlementParticipantRegistrationService participantRegistrationService;
    private final SettlementAmountCalculator settlementAmountCalculator;
    private final SettlementRepository settlementRepository;
    private final UserRepository userRepository;
    @Transactional
    public CreateSharedSettlementResponse create(
            Long ownerId,
            CreateSharedSettlementRequest request
    ) {
       settlementValidator.validateCreateRequest(request);

       User owner = userRepository.findById(ownerId)
               .orElseThrow(
                       UserErrorCode.USER_NOT_FOUND::toException
               );
        BigDecimal perPersonAmount =
                settlementAmountCalculator.calculateEqualAmount(
                        request.getTotalAmount(),
                        request.getParticipants().size()
                );

        LocalDateTime createdAt = LocalDateTime.now();

        Settlement settlement =
                Settlement.builder()
                        .owner(owner)
                        .settlementType(SettlementType.SHARED)
                        .settlementStatus(
                                SettlementStatus.IN_PROGRESS
                        )
                        .settlementCategory(
                                request.getSettlementCategory()
                        )
                        .title(request.getTitle())
                        .splitType(SplitType.EQUAL)

                        .totalAmount(request.getTotalAmount())
                        .dueDate(request.getDueDate())
                        .createdAt(createdAt)
                        .build();

        Settlement savedSettlement =
                settlementRepository.save(settlement);

        participantRegistrationService.registerParticipants(
                ownerId,
                savedSettlement.getSettlementId(),
                request.getParticipants(),
                perPersonAmount
        );

        settlementAccountService.selectAccount(
                ownerId,
                savedSettlement.getSettlementId(),
                request.getLinkedAccountId()
        );

        return CreateSharedSettlementResponse.builder()
                .settlementId(savedSettlement.getSettlementId())
                .settlementType(savedSettlement.getSettlementType())
                .settlementStatus(savedSettlement.getSettlementStatus())
                .title(savedSettlement.getTitle())
                .createdAt(savedSettlement.getCreatedAt())
                .build();
    }
}