package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateRecurringSettlementRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateRecurringSettlementResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.RecurringSettlementRepository;
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
public class RecurringSettlementService {
    private final RecurringSettlementRepository recurringSettlementRepository;

    private final SettlementRepository settlementRepository;

    private final RecurringSettlementValidator recurringSettlementValidator;

    private final SettlementAmountCalculator settlementAmountCalculator;

    private final SettlementParticipantRegistrationService participantRegistrationService;

    private final SettlementAccountService settlementAccountService;

    private final UserRepository userRepository;

    @Transactional
    public CreateRecurringSettlementResponse create(
            Long ownerId, CreateRecurringSettlementRequest request
    ){
        recurringSettlementValidator.validateCreateRequest(request);

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

        RecurringSettlement recurringSettlement =
                RecurringSettlement.builder()
                        .owner(owner)
                        .settlementCategory(request.getSettlementCategory())
                        .title(request.getTitle())
                        .splitType(SplitType.EQUAL)
                        .totalAmount(request.getTotalAmount())
                        .cycleRule(request.getCycleRule())
                        .startDate(request.getStartDate())
                        .endDate(request.getEndDate())
                        .createdAt(createdAt)
                        .build();
        RecurringSettlement savedRecurringSettlement =
                recurringSettlementRepository.save(recurringSettlement);

        Settlement firstSettlement =
                Settlement.builder()
                        .recurringSettlement(savedRecurringSettlement)
                        .owner(owner)
                        .settlementType(SettlementType.RECURRING)
                        .settlementStatus(SettlementStatus.IN_PROGRESS)
                        .settlementCategory(request.getSettlementCategory())
                        .title(request.getTitle())
                        .splitType(SplitType.EQUAL)
                        .totalAmount(request.getTotalAmount())
                        .dueDate(null)
                        .cycleDate(request.getStartDate())
                        .createdAt(createdAt)
                        .build();

        Settlement savedFirstSettlement =
                settlementRepository.save(firstSettlement);

        participantRegistrationService.registerParticipants(
                ownerId,savedFirstSettlement.getSettlementId(),request.getParticipants(),perPersonAmount
        );

        settlementAccountService.selectAccount(
                ownerId, savedFirstSettlement.getSettlementId(), request.getLinkedAccountId());

        return SettlementAssembler.toCreateRecurringSettlementResponse(savedRecurringSettlement, savedFirstSettlement);
    }
}
