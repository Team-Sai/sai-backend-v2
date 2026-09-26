package org.teamsai.saibackend.global.client;

public class BankKeyRecoveryException extends RuntimeException {
    public enum Reason { EXPIRED, CONFLICT }
    private final Reason reason;

    public BankKeyRecoveryException(Reason reason, Throwable cause) {
        super("Bank key recovery rejected: " + reason, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
