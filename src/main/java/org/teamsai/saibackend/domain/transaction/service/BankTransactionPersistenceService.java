package org.teamsai.saibackend.domain.transaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.transaction.dto.BankTransactionDTO;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionBulkRepository;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransactionPersistenceService {

    private final BankTransactionBulkRepository bankTransactionBulkRepository;
    private final LinkedBankAccountService linkedBankAccountService;

    @Transactional
    public int saveAndAdvanceCursor(
            Long linkedAccountId,
            List<BankTransactionDTO> transactions) {
        if (transactions.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        List<BankTransaction> entities = transactions.stream()
                .map(tx -> toEntity(linkedAccountId, tx, now))
                .toList();

        // 모든 묶음과 커서 갱신은 이 메서드의 동일 트랜잭션에 참여한다.
        bankTransactionBulkRepository.insertAllIfAbsent(entities);

        BankTransactionDTO latestTransaction =
                transactions.stream()
                .max(Comparator.comparing(
                        BankTransactionDTO::transactionId))
                .orElseThrow();
        linkedBankAccountService.advanceTransactionCursor(
                linkedAccountId,
                latestTransaction.transactionId(),
                latestTransaction.balanceAfter()
        );
        
        // 신규 INSERT 수가 아니라 중복 거래를 포함한 이번 요청의 처리 대상 수다.
        return transactions.size();
    }

    private BankTransaction toEntity(Long linkedAccountId, BankTransactionDTO tx, LocalDateTime syncedAt) {
        // Entity 생성자가 초기 상태 PENDING과 재시도 횟수 0을 설정한다.
        return new BankTransaction(
                linkedAccountId,
                tx.transactionKey(),
                tx.amount(),
                toTransactionType(linkedAccountId, tx),
                tx.transactionAt(),
                tx.counterpartyName(),
                tx.memo(),
                syncedAt
        );
    }

    private BankTransactionType toTransactionType(Long linkedAccountId, BankTransactionDTO tx) {
        try {
            if (tx.transactionType() == null) {
                throw new IllegalArgumentException("Missing transaction type");
            }
            return switch (tx.transactionType()) {
                case "DEPOSIT" -> BankTransactionType.DEPOSIT;
                case "WITHDRAW", "WITHDRAWAL" -> BankTransactionType.WITHDRAWAL;
                default -> throw new IllegalArgumentException(
                        "Unsupported bank transaction type: " + tx.transactionType()
                );
            };
        } catch (IllegalArgumentException e) {
            log.error(
                    "[BankTransactionPersistenceService] 사이은행 응답의 거래유형이 올바르지 않음 - "
                            + "linkedAccountId: {}, externalTransactionId: {}, transactionType: {}",
                    linkedAccountId, tx.transactionKey(), tx.transactionType()
            );
            throw AccountErrorCode.INVALID_BANK_RESPONSE.toException();
        }
    }
}
