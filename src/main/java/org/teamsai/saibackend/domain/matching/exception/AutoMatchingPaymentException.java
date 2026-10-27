package org.teamsai.saibackend.domain.matching.exception;

import org.teamsai.saibackend.domain.matching.model.EvaluatedMatchingCandidate;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.Objects;

public class AutoMatchingPaymentException extends RuntimeException {

    private final EvaluatedMatchingCandidate candidate;
    private final DomainException paymentFailure;

    public AutoMatchingPaymentException(
            EvaluatedMatchingCandidate candidate,
            DomainException cause
    ) {
        super(Objects.requireNonNull(cause, "paymentFailure must not be null").getMessage(), cause);
        this.candidate = candidate;
        this.paymentFailure = cause;
    }

    public EvaluatedMatchingCandidate getCandidate() {
        return candidate;
    }

    public DomainException getPaymentFailure() {
        return paymentFailure;
    }
}
