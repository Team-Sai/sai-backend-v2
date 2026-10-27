package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.matching.entity.BankTransactionMatchCandidate;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingTransactionResult;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingProcessStatus;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchingReviewNotificationService {

    private final PaymentObligationQueryService paymentObligationQueryService;
    private final BankTransactionMatchCandidateService candidateService;
    private final NotificationService notificationService;
    private final SettlementPaymentStatusChecker paymentStatusChecker;

    public void notifyIfRequired(
            Long userId,
            Long linkedAccountId,
            BankTransaction bankTransaction,
            AutoMatchingTransactionResult result,
            boolean isBatch
    ) {
        if (result.processStatus() != AutoMatchingProcessStatus.NEEDS_CHECK) {
            return;
        }

        List<BankTransactionMatchCandidate> candidates =
                candidateService.findAllByBankTransactionId(
                        bankTransaction.getBankTransactionId()
                );
        boolean hasSettlement = candidates.stream()
                .anyMatch(candidate -> candidate.getTargetType() == MatchingTargetType.SETTLEMENT);
        boolean hasLoan = candidates.stream()
                .anyMatch(candidate -> candidate.getTargetType() == MatchingTargetType.LOAN);

        if (hasSettlement && hasLoan) {
            notificationService.createIfAbsent(
                    userId,
                    NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                    "입금 거래 확인이 필요합니다.",
                    createBothFoundContent(bankTransaction),
                    bankTransaction.getBankTransactionId(),
                    linkedAccountId
            );
            return;
        }

        if (!isBatch) {
            return;
        }

        List<Long> settlementObligationIds = candidates.stream()
                .filter(candidate -> candidate.getTargetType() == MatchingTargetType.SETTLEMENT)
                .map(BankTransactionMatchCandidate::getTargetId)
                .toList();
        if (settlementObligationIds.isEmpty()) {
            return;
        }

        List<Long> settlementIds = paymentObligationQueryService
                .findSettlementIdsByObligationIds(settlementObligationIds);
        boolean hasUnresolvedSettlement = settlementIds.stream()
                .anyMatch(settlementId ->
                        !paymentStatusChecker.areAllObligationsResolved(settlementId));
        if (hasUnresolvedSettlement) {
            notificationService.createIfAbsent(
                    userId,
                    NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                    "정산이 완납되지 않았습니다.",
                    createUnresolvedSettlementContent(bankTransaction),
                    bankTransaction.getBankTransactionId(),
                    linkedAccountId
            );
        }
    }

    private String createBothFoundContent(BankTransaction bankTransaction) {
        return resolveCounterpartyName(bankTransaction)
                + "님의 "
                + bankTransaction.getAmount().toPlainString()
                + "원 입금에 정산과 차용증 후보가 모두 발견되었습니다.";
    }

    private String createUnresolvedSettlementContent(BankTransaction bankTransaction) {
        return resolveCounterpartyName(bankTransaction)
                + "님의 "
                + bankTransaction.getAmount().toPlainString()
                + "원 입금이 정산 금액과 일치하지 않아 확인이 필요합니다.";
    }

    private String resolveCounterpartyName(BankTransaction bankTransaction) {
        String counterpartyName = bankTransaction.getCounterpartyName();
        return counterpartyName == null || counterpartyName.isBlank()
                ? "입금자 미상"
                : counterpartyName;
    }
}
