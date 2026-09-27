package org.teamsai.saibackend.domain.link.dto.response;

public record AccountLinkCallbackResult(boolean success, String errorMessage) {
    public static AccountLinkCallbackResult completed() {
        return new AccountLinkCallbackResult(true, null);
    }

    public static AccountLinkCallbackResult failed(String message) {
        return new AccountLinkCallbackResult(false, message);
    }
}
