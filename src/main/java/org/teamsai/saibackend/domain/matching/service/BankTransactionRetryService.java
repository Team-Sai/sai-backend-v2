package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.batch.common.notification.SlackNotifier;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;
import org.teamsai.saibackend.domain.matching.type.RetryPolicy;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransactionRetryService {

    private final BankTransactionService bankTransactionService;
    private final BankTransactionMatchCandidateService candidateService;
    private final BankMatchingService bankMatchingService;
    private final SlackNotifier slackNotifier;

    public void retryForAccount(Long userId, Long linkedAccountId) {
        List<BankTransactionEntity> candidates =
                bankTransactionService.findRetryCandidates(linkedAccountId);

        if (candidates.isEmpty()) {
            return;
        }

        for (BankTransactionEntity tx : candidates) {
            candidateService.deleteAllByBankTransactionId(tx.getBankTransactionId());
            bankTransactionService.resetToPendingForRetry(
                    tx.getBankTransactionId(), tx.getProcessingStatus());
        }

        AutoMatchingExecutionResult result =
                bankMatchingService.execute(userId, linkedAccountId, true);

        log.info("[bankTransactionRetry] linkedAccountId={}, 재시도 {}건 중 성공 {}건",
                linkedAccountId, candidates.size(), result.appliedCount());

        checkAndNotifyExhausted(candidates);
    }

    private void checkAndNotifyExhausted(List<BankTransactionEntity> retriedTransactions) {
        for (BankTransactionEntity original : retriedTransactions) {
            BankTransactionEntity current = bankTransactionService.findById(
                    original.getBankTransactionId()
            ).orElse(null);

            if (current == null || current.getProcessingStatus() == BankTransactionProcessingStatus.APPLIED) {
                continue; // 조회 실패했거나 성공했으면 알림 불필요
            }

            int maxRetry = RetryPolicy.maxRetryCount(current.getProcessingStatus());
            if (current.getRetryCount() >= maxRetry) {
                slackNotifier.send(String.format(
                        "⚠️ *은행거래 매칭 재시도 소진* — bankTransactionId=%d, status=%s, retryCount=%d/%d",
                        current.getBankTransactionId(),
                        current.getProcessingStatus(),
                        current.getRetryCount(),
                        maxRetry
                ));
            }
        }
    }
}
