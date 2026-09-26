package org.teamsai.saibackend.domain.matching.support;

import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.matching.exception.MatchingErrorCode;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

@Component
public class MatchingReviewValidator {

    public void validateReviewable(BankTransactionDetailResponse transaction) {
        if (transaction.processingStatus()
                != BankTransactionProcessingStatus.NEEDS_CHECK
                || transaction.transactionType()
                != BankTransactionType.DEPOSIT) {
            throw MatchingErrorCode
                    .MATCHING_REVIEW_NOT_REQUIRED
                    .toException();
        }
    }
}
