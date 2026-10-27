package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.type.RetryPolicy;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;

@Slf4j
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
            log.warn(
                    "[RetryPrepareService] 재시도 준비 건너뜀 (거래 없음 또는 상태/횟수 불일치) "
                            + "- bankTransactionId={}, linkedAccountId={}, expectedStatus={}, expectedRetryCount={}",
                    bankTransactionId,
                    linkedAccountId,
                    expectedStatus,
                    expectedRetryCount
            );
            return false;
        }

        candidateService.deleteAllByBankTransactionId(bankTransactionId);

        return true;
    }
}
