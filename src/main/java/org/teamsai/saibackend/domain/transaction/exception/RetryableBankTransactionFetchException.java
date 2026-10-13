package org.teamsai.saibackend.domain.transaction.exception;

import org.springframework.http.HttpStatus;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;

public class RetryableBankTransactionFetchException
        extends DomainException {

    public RetryableBankTransactionFetchException(Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                AccountErrorCode.BANK_SERVER_UNAVAILABLE
        );
        initCause(cause);
    }
}