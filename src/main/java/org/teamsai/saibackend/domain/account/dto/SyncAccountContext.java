package org.teamsai.saibackend.domain.account.dto;

public record SyncAccountContext(
        Long userId,
        Long bankAccountId,
        String userKey,
        Long lastSyncedTransactionId
) {
}
