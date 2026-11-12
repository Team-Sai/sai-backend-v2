package org.teamsai.saibackend.domain.transaction.repository;

import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;

import java.util.List;

public interface BankTransactionBulkRepository {

    /**
     * Insert received transactions in input order, preserving existing rows on duplicate keys.
     * Requires the caller's write transaction; all chunks must roll back with cursor updates.
     * Does not populate generated IDs or return an inserted-row count.
     */
    void insertAllIfAbsent(List<BankTransaction> transactions);
}
