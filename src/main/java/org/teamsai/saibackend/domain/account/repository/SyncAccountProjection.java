package org.teamsai.saibackend.domain.account.repository;

public interface SyncAccountProjection {
    Long getUserId();

    Long getBankAccountId();

    String getUserKey();

    Long getLastSyncedTransactionId();
}
