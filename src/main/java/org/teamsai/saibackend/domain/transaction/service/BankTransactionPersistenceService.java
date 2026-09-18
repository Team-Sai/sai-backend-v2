package org.teamsai.saibackend.domain.transaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionResponse;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransactionPersistenceService {

    private final BankTransactionRepository bankTransactionRepository;
    private final LinkedBankAccountRepository linkedBankAccountRepository;

    @Transactional
    public int saveAndAdvanceCursor(
            Long linkedAccountId,
            List<BankTransactionResponse> transactions) {
        if (transactions.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        for (BankTransactionResponse tx : transactions) {
            BankTransactionEntity transaction = toEntity(linkedAccountId, tx, now);

            // 동기화에서는 저장 ID가 필요 없으므로 중복을 허용하는 저장만 실행한다.
            bankTransactionRepository.insertIfAbsent(
                    transaction.getLinkedAccountId(),
                    transaction.getExternalTransactionId(),
                    transaction.getAmount(),
                    transaction.getTransactionType().name(),
                    transaction.getTransactionAt(),
                    transaction.getCounterpartyName(),
                    transaction.getMemo(),
                    transaction.getSyncedAt()
            );
        }

        BankTransactionResponse latestTransaction =
                transactions.stream()
                .max(Comparator.comparing(
                        BankTransactionResponse::transactionId))
                .orElseThrow();

        linkedBankAccountRepository.advanceCursorAndBalance(
                linkedAccountId, latestTransaction.transactionId(), latestTransaction.balanceAfter());

        // 신규 INSERT 수가 아니라 중복 거래를 포함한 이번 요청의 처리 대상 수다.
        return transactions.size();
    }

    private BankTransactionEntity toEntity(Long linkedAccountId, BankTransactionResponse tx, LocalDateTime syncedAt) {
        // Entity 생성자가 초기 상태 PENDING과 재시도 횟수 0을 설정한다.
        return new BankTransactionEntity(
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

    private BankTransactionType toTransactionType(Long linkedAccountId, BankTransactionResponse tx) {
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
