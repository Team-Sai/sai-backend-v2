package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementQueryService {

    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;

    @Transactional(readOnly = true)
    public List<SettlementListResponse> getSettlementList(Long userId) {
        return settlementRepository.findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                )
                .stream()
                .map(settlement -> {
                    RecurringSettlement recurringSettlement =
                            settlement.getRecurringSettlement();

                    String role =
                            settlement.getOwner().getUserId().equals(userId)
                                    ? "OWNER"
                                    : "MEMBER";

                    return new SettlementListResponse(
                            settlement.getSettlementId(),
                            settlement.getTitle(),
                            role,
                            settlement.getSettlementCategory(),
                            settlement.getSettlementType().name(),
                            settlement.getSplitType() != null
                                    ? settlement.getSplitType().name()
                                    : null,
                            settlement.getSettlementStatus().name(),
                            settlement.getTotalAmount(),
                            settlement.getDueDate(),
                            recurringSettlement != null
                                    ? recurringSettlement.getStartDate()
                                    : null,
                            recurringSettlement != null
                                    ? recurringSettlement.getEndDate()
                                    : null,
                            settlement.getCycleDate(),
                            settlement.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SettlementDetailResponse getSettlementDetail(Long settlementId, Long userId){
        Settlement settlement =
                settlementRepository.findDetailById(settlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                                        ::toException
                        );

        String role;

        if (settlement.getOwner().getUserId().equals(userId)) {
            role = "OWNER";
        } else {
            boolean isParticipant =
                    settlementParticipantRepository.existsActiveParticipant(
                            settlementId,
                            userId,
                            SettlementParticipantStatus.ACTIVE
                    );

            if (!isParticipant) {
                throw SettlementErrorCode
                        .SETTLEMENT_ACCESS_DENIED
                        .toException();
            }

            role = "MEMBER";
        }

        RecurringSettlement recurringSettlement =
                settlement.getRecurringSettlement();

        return new SettlementDetailResponse(
                settlement.getSettlementId(),
                settlement.getTitle(),
                settlement.getOwner().getName(),
                settlement.getSettlementCategory(),
                settlement.getSettlementType().name(),
                settlement.getSettlementStatus().name(),
                settlement.getSplitType() != null
                        ? settlement.getSplitType().name()
                        : null,
                settlement.getDueDate(),
                recurringSettlement != null
                        ? recurringSettlement.getStartDate()
                        : null,
                recurringSettlement != null
                        ? recurringSettlement.getEndDate()
                        : null,
                settlement.getCreatedAt(),
                role
        );
    }
}