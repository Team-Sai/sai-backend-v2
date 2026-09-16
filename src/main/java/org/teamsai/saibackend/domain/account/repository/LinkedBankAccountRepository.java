package org.teamsai.saibackend.domain.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.dto.LinkedAccountSyncTargetDTO;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LinkedBankAccountRepository extends JpaRepository<LinkedBankAccount, Long>, LinkedBankAccountRepositoryCustom {

    List<LinkedBankAccount> findAllByUserId(@Param("userId") Long userId);

    List<LinkedBankAccount> findAllByUserIdAndConnectionStatus(
            Long userId,
            ConnectionStatus connectionStatus
    );

    Optional<LinkedBankAccount> findByUserIdAndAccountId(
            Long userId,
            Long accountId
    );

    @Query("""
            select a.lastSyncedTransactionId
            from LinkedBankAccount a
            where a.linkedAccountId = :linkedAccountId
            """)
    Long findLastSyncedTransactionIdById(
            @Param("linkedAccountId") Long linkedAccountId
    );

    @Modifying
    @Query("""
        update LinkedBankAccount a
        set a.lastSyncedTransactionId = :transactionId
        where a.linkedAccountId = :linkedAccountId
          and (
                a.lastSyncedTransactionId is null
                or a.lastSyncedTransactionId < :transactionId
              )
        """)
    int updateLastSyncedTransactionId(
            @Param("linkedAccountId") Long linkedAccountId,
            @Param("transactionId") Long transactionId
    );

    @Modifying
    @Query("""
        update LinkedBankAccount a
        set a.balance = :balance,
            a.updatedAt = CURRENT_TIMESTAMP
        where a.linkedAccountId = :linkedAccountId
        """)
    int updateBalance(
            @Param("linkedAccountId") Long linkedAccountId,
            @Param("balance") BigDecimal balance
    );
}
