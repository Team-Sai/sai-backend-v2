package org.teamsai.saibackend.domain.contract.dto.response;

import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EarlyRepaymentDepositResponse(
        Long bankTransactionId,
        BigDecimal amount,
        LocalDateTime transactionAt,
        String counterpartyName,
        BigDecimal shortfallAmount,
        BigDecimal accruedInterest,
        BigDecimal balance,
        boolean memoConfirmed
) {
    public static EarlyRepaymentDepositResponse of(
            BankTransaction transaction, BigDecimal shortfallAmount,
            BigDecimal accruedInterest, BigDecimal balance, boolean memoConfirmed
    ) {
        return new EarlyRepaymentDepositResponse(
                transaction.getBankTransactionId(), transaction.getAmount(),
                transaction.getTransactionAt(), transaction.getCounterpartyName(),
                shortfallAmount, accruedInterest, balance, memoConfirmed);
    }
}