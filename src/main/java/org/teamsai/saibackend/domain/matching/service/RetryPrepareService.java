package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.type.RetryPolicy;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;

@Service
@RequiredArgsConstructor
public class RetryPrepareService {

    private final BankTransactionService bankTransactionService;
    private final BankTransactionMatchCandidateService candidateService;

    @Transactional
    public boolean prepare(
            Long linkedAccountId,
            Long bankTransactionId,
            BankTransactionProcessingStatus expectedStatus,
            int expectedRetryCount
    ) {
        int maxRetryCount = RetryPolicy.maxRetryCount(
                expectedStatus
        );

        if (expectedRetryCount >= maxRetryCount) {
            return false;
        }

        int updated = bankTransactionService.resetToPendingForRetry(
                bankTransactionId,
                linkedAccountId,
                expectedStatus,
                expectedRetryCount
        );

        if (updated == 0) {
            return false;
        }

        candidateService.deleteAllByBankTransactionId(bankTransactionId);

        return true;
    }
}
