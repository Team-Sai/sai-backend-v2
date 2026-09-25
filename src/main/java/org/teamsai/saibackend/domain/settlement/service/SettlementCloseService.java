package org.teamsai.saibackend.domain.settlement.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementCloseResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import java.time.LocalDateTime;
@Service
@RequiredArgsConstructor
public class SettlementCloseService {
    private final SettlementRepository settlementRepository;
    private final SettlementPaymentStatusChecker paymentStatusChecker;
    private final SettlementValidator settlementValidator;
    
    @Transactional
    public SettlementCloseResponse close(Long settlementId, Long userId){
        Settlement settlement =
                settlementRepository.findByIdForUpdate(settlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                                        ::toException
                        );
        settlementValidator.validateOwner(settlement,userId);
        if(settlement.getSettlementStatus()
                != SettlementStatus.IN_PROGRESS){
            throw SettlementErrorCode
                    .ALREADY_CLOSED_SETTLEMENT
                    .toException();
        }
        if(!paymentStatusChecker.areAllObligationsResolved(settlementId)){
            throw SettlementErrorCode.SETTLEMENT_NOT_CLOSABLE.toException();
        }
        LocalDateTime closedAt = LocalDateTime.now();
        int updatedCount =
                settlementRepository.closeSettlement(
                        settlementId,
                        closedAt,
                        SettlementStatus.IN_PROGRESS,
                        SettlementStatus.CLOSED
                );
        if(updatedCount != 1){
            throw SettlementErrorCode
                    .SETTLEMENT_CLOSE_FAILED
                    .toException();
        }
        return SettlementCloseResponse.builder()
                .settlementId(settlementId)
                .settlementStatus(SettlementStatus.CLOSED)
                .closedAt(closedAt)
                .build();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoCloseIfAllResolved(Long settlementId) {
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(SettlementErrorCode.SETTLEMENT_NOT_FOUND::toException);
        if (settlement.getSettlementStatus() != SettlementStatus.IN_PROGRESS) {
            return false;
        }
        if (!paymentStatusChecker.areAllObligationsResolved(settlementId)) {
            return false;
        }
        LocalDateTime closedAt = LocalDateTime.now();
        int updatedCount =
                settlementRepository.closeSettlement(
                        settlementId,
                        closedAt,
                        SettlementStatus.IN_PROGRESS,
                        SettlementStatus.CLOSED
                );
        return updatedCount == 1;
    }


}
