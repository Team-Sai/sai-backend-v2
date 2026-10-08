package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCycleListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCycleResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.RecurringSettlementRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecurringSettlementQueryService {

    private final RecurringSettlementRepository recurringSettlementRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final SettlementQueryService settlementQueryService;

    @Transactional(readOnly = true)
    public RecurringSettlementCycleListResponse getCycles(
            Long recurringSettlementId,
            Long userId
    ) {
        RecurringSettlement recurringSettlement =
                recurringSettlementRepository.findById(recurringSettlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .RECURRING_SETTLEMENT_NOT_FOUND
                                        ::toException
                        );

        boolean isOwner =
                recurringSettlement.getOwner().getUserId().equals(userId);

        Set<Long> participatingSettlementIds = isOwner
                ? Set.of()
                : new HashSet<>(
                        settlementParticipantRepository.findSettlementIdsByRecurringIdAndUserId(
                                recurringSettlementId,
                                userId,
                                SettlementParticipantStatus.ACTIVE
                        )
                );

        if (!isOwner && participatingSettlementIds.isEmpty()) {
            throw SettlementErrorCode
                    .SETTLEMENT_ACCESS_DENIED
                    .toException();
        }

        List<Settlement> settlements =
                settlementRepository.findAllByRecurringIdOrderByCycleDate(
                        recurringSettlementId
                );

        List<Settlement> visibleSettlements = settlements.stream()
                .filter(settlement -> isOwner
                        || participatingSettlementIds.contains(settlement.getSettlementId()))
                .toList();

        Map<Long, SettlementPaymentStatusResponse> paymentStatusBySettlementId =
                settlementQueryService.readPaymentStatuses(visibleSettlements);

        List<RecurringSettlementCycleResponse> cycles = new ArrayList<>();


        for (int i = 0; i < settlements.size(); i++) {
            Settlement settlement = settlements.get(i);
            SettlementPaymentStatusResponse paymentStatus =
                    paymentStatusBySettlementId.get(settlement.getSettlementId());

            if (paymentStatus == null) {
                continue;
            }

            cycles.add(
                    SettlementAssembler.toCycleResponse(
                            settlement,
                            i + 1,
                            paymentStatus
                    )
            );
        }

        cycles.sort((a, b) -> Integer.compare(b.getCycleNo(), a.getCycleNo()));

        return SettlementAssembler.toCycleListResponse(
                recurringSettlement,
                isOwner ? "OWNER" : "MEMBER",
                settlements.size(),
                cycles
        );
    }
}
