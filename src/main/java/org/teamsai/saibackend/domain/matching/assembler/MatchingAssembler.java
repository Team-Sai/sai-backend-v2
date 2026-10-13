package org.teamsai.saibackend.domain.matching.assembler;

import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionReviewQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchCandidateResponse;
import org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse;
import org.teamsai.saibackend.domain.matching.type.MatchingReviewChannel;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;

import java.util.List;

/**
 * 은행 거래(transaction) + 매칭 후보(candidates) 데이터를 조합해서
 * 매칭 검토 응답 DTO를 만드는 조립 전담 클래스.
 */
public final class MatchingAssembler {

    private MatchingAssembler() {
    }

    public static BankTransactionMatchingReviewResponse toReviewResponse(
            BankTransactionReviewQueryDTO transaction,
            List<BankTransactionMatchCandidateQueryDTO> candidates
    ) {
        return toReviewResponse(toTransactionDetail(transaction), candidates);
    }

    public static BankTransactionMatchingReviewResponse toReviewResponse(
            BankTransactionDetailResponse transaction,
            List<BankTransactionMatchCandidateQueryDTO> candidates
    ) {
        return new BankTransactionMatchingReviewResponse(
                transaction,
                determineReviewChannel(candidates),
                candidates.stream()
                        .map(BankTransactionMatchCandidateResponse::from)
                        .toList()
        );
    }

    private static BankTransactionDetailResponse toTransactionDetail(BankTransactionReviewQueryDTO dto) {
        return new BankTransactionDetailResponse(
                dto.bankTransactionId(),
                dto.linkedAccountId(),
                dto.amount(),
                dto.transactionType(),
                dto.processingStatus(),
                dto.transactionAt(),
                dto.counterpartyName(),
                dto.memo(),
                dto.syncedAt()
        );
    }

    private static MatchingReviewChannel determineReviewChannel(
            List<BankTransactionMatchCandidateQueryDTO> candidates
    ) {
        return MatchingReviewChannel.TRANSACTION_HISTORY;
    }
}
