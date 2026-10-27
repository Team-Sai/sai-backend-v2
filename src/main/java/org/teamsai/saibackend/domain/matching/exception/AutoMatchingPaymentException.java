package org.teamsai.saibackend.domain.matching.exception;

import org.teamsai.saibackend.domain.matching.model.EvaluatedMatchingCandidate;
import org.teamsai.saibackend.global.exception.DomainException;

public class AutoMatchingPaymentException extends RuntimeException {

    private final EvaluatedMatchingCandidate candidate;

    public AutoMatchingPaymentException(
            EvaluatedMatchingCandidate candidate,
            DomainException cause
    ) {
        super(cause.getMessage(), cause);
        this.candidate = candidate;
    }

    public EvaluatedMatchingCandidate getCandidate() {
        return candidate;
    }

    public DomainException getPaymentFailure() {
        return (DomainException) getCause();
    }
}
