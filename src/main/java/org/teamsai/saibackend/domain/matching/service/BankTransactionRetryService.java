package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.teamsai.saibackend.global.notification.SlackNotifier;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;
import org.teamsai.saibackend.domain.matching.type.RetryPolicy;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransactionRetryService {

    private final BankTransactionService bankTransactionService;
    private final RetryPrepareService retryPrepareService;
    private final BankMatchingService bankMatchingService;
    private final SlackNotifier slackNotifier;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retryForAccount(Long userId, Long linkedAccountId) {
        List<BankTransaction> candidates =
                bankTransactionService.findRetryCandidates(linkedAccountId);

        if (candidates.isEmpty()) {
            return;
        }

        List<BankTransaction> preparedTransactions = new ArrayList<>();

        for (BankTransaction transaction : candidates) {
            boolean prepared = retryPrepareService.prepare(
                    linkedAccountId,
                    transaction.getBankTransactionId(),
                    transaction.getProcessingStatus(),
                    transaction.getRetryCount()
            );

            if (prepared) {
                preparedTransactions.add(transaction);
            }
        }

        AutoMatchingExecutionResult result =
                bankMatchingService.execute(userId, linkedAccountId, true);

        log.info(
                "[bankTransactionRetry] linkedAccountId={}, "
                        + "재시도 준비 {}건, 계좌 전체 매칭 성공 {}건",
                linkedAccountId,
                preparedTransactions.size(),
                result.appliedCount()
        );

        checkAndNotifyExhausted(preparedTransactions);
    }

    private void checkAndNotifyExhausted(List<BankTransaction> retriedTransactions) {
        for (BankTransaction original : retriedTransactions) {
            BankTransaction current = bankTransactionService.findById(
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
