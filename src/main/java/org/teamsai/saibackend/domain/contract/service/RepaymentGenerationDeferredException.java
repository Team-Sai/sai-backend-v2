package org.teamsai.saibackend.domain.contract.service;

public class RepaymentGenerationDeferredException
        extends RuntimeException {

    private final String reason;
    private final Integer retryAfterSeconds;

    public RepaymentGenerationDeferredException(String reason) {
        this(reason, null);
    }

    public RepaymentGenerationDeferredException(
            String reason,
            Integer retryAfterSeconds
    ) {
        super(reason);

        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String reason() {
        return reason;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}