package org.teamsai.saibackend.domain.transaction.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.transaction.dto.request.BankTransactionSearchCondition;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionListItemResponse;
import org.teamsai.saibackend.domain.transaction.dto.response.PageResponse;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionQueryRepository;

import java.time.LocalDateTime;
import java.util.List;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.transaction.dto.response.IntegratedBankTransactionResponse;

@Service
@RequiredArgsConstructor
public class BankTransactionQueryService {

    private final BankTransactionRepository bankTransactionRepository;
    private final BankTransactionQueryRepository bankTransactionQueryRepository;

    private final LinkedBankAccountService linkedBankAccountService;

    @Transactional(readOnly = true)
    public PageResponse<IntegratedBankTransactionResponse> getIntegratedTransactions(
            Long userId, Long linkedAccountId, BankTransactionSearchCondition condition
    ) {
        if (linkedAccountId != null) {
            validateOwnership(userId, linkedAccountId);
        }
        Page<IntegratedBankTransactionResponse> result = bankTransactionQueryRepository.searchIntegrated(
                userId, ConnectionStatus.AVAILABLE, linkedAccountId,
                condition.processingStatus(), condition.transactionType(), condition.keyword(),
                condition.fromDate() == null ? null : condition.fromDate().atStartOfDay(),
                condition.toDate() == null ? null : condition.toDate().plusDays(1).atStartOfDay(),
                PageRequest.of(Math.toIntExact(condition.page()), Math.toIntExact(condition.size())));
        return PageResponse.of(result.getContent(), condition.page(), condition.size(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<BankTransactionListItemResponse> getTransactions(
            Long userId,
            Long linkedAccountId,
            BankTransactionSearchCondition condition
    ) {
        validateOwnership(userId, linkedAccountId);

        LocalDateTime fromDateTime = condition.fromDate() == null
                ? null
                : condition.fromDate().atStartOfDay();

        LocalDateTime toDateTimeExclusive = condition.toDate() == null
                ? null
                : condition.toDate().plusDays(1).atStartOfDay();

        PageRequest pageable = PageRequest.of(
                Math.toIntExact(condition.page()),
                Math.toIntExact(condition.size())
        );

        Page<BankTransactionEntity> transactions =
                bankTransactionQueryRepository.search(
                        linkedAccountId,
                        condition.processingStatus(),
                        condition.transactionType(),
                        condition.keyword(),
                        fromDateTime,
                        toDateTimeExclusive,
                        pageable
                );

        List<BankTransactionListItemResponse> content =
                transactions.getContent().stream()
                        .map(BankTransactionListItemResponse::from)
                        .toList();

        return PageResponse.of(
                content,
                condition.page(),
                condition.size(),
                transactions.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public BankTransactionDetailResponse getTransactionDetail(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId
    ) {
        validateOwnership(userId, linkedAccountId);

        BankTransactionEntity transaction = bankTransactionRepository
                .findByBankTransactionIdAndLinkedAccountId(
                        bankTransactionId,
                        linkedAccountId
                )
                .orElseThrow(
                        BankTransactionErrorCode
                                .BANK_TRANSACTION_NOT_FOUND::toException
                );

        return BankTransactionDetailResponse.from(transaction);
    }


    private void validateOwnership(
            Long userId,
            Long linkedAccountId
    ) {
        LinkedBankAccount linkedAccount = linkedBankAccountService.getLinkedAccount(linkedAccountId);

        if (!linkedAccount.getUserId().equals(userId)) {
            throw AccountErrorCode.ACCOUNT_ACCESS_DENIED.toException();
        }
    }
}
