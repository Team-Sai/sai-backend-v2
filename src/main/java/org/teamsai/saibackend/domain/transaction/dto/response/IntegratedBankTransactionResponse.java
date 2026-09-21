package org.teamsai.saibackend.domain.transaction.dto.response;

import org.teamsai.saibackend.domain.account.util.BankCodeResolver;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;
import org.teamsai.saibackend.global.util.MaskingUtil;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;

public record IntegratedBankTransactionResponse(
        Long bankTransactionId, Long linkedAccountId, BigDecimal amount,
        BankTransactionType transactionType, BankTransactionProcessingStatus processingStatus,
        LocalDateTime transactionAt, String counterpartyName, String memo,
        String bankName, String maskedAccountNumber
) {
    public IntegratedBankTransactionResponse(BankTransactionEntity transaction, String bankCode, String accountNumber) {
        this(transaction.getBankTransactionId(), transaction.getLinkedAccountId(), transaction.getAmount(),
                transaction.getTransactionType(), transaction.getProcessingStatus(), transaction.getTransactionAt(),
                transaction.getCounterpartyName(), transaction.getMemo(),
                BankCodeResolver.resolveBankName(bankCode), MaskingUtil.maskAccountNumber(accountNumber));
    }
}
