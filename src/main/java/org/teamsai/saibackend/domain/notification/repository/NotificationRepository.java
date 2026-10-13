package org.teamsai.saibackend.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.notification.dto.response.NotificationResponse;
import org.teamsai.saibackend.domain.notification.entity.Notification;
import org.teamsai.saibackend.domain.notification.type.NotificationType;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByUser_UserIdAndNotificationTypeAndReferenceId(
            Long userId,
            NotificationType notificationType,
            Long referenceId
    );

    @Query(value = """
            SELECT
                n.notification_id AS notificationId,
                n.notification_type AS notificationType,
                n.title AS title,
                n.content AS content,
                n.reference_id AS referenceId,
                n.secondary_reference_id AS secondaryReferenceId,
                NULL AS referenceTitle,
                NULL AS referenceType,
                NULL AS settlementType,
                bt.processing_status AS relatedTransactionStatus,
                CASE
                    WHEN bt.processing_status IS NOT NULL
                     AND bt.processing_status != 'NEEDS_CHECK'
                    THEN TRUE
                    ELSE FALSE
                END AS resolvedFlag,
                n.created_at AS createdAt
            FROM notification n
            LEFT JOIN bank_transaction bt
                ON bt.bank_transaction_id = n.reference_id
                AND bt.linked_account_id = n.secondary_reference_id
            WHERE n.user_id = :userId
              AND n.notification_type = 'BANK_TRANSACTION_MATCHING_REVIEW'
            ORDER BY n.created_at DESC, n.notification_id DESC
            """, nativeQuery = true)
    List<NotificationResponse> findBankTransactionNotificationsByUserId(
            @Param("userId") Long userId
    );

    @Query(value = """
            SELECT
                n.notification_id AS notificationId,
                n.notification_type AS notificationType,
                n.title AS title,
                n.content AS content,
                n.reference_id AS referenceId,
                n.secondary_reference_id AS secondaryReferenceId,
                s.title AS referenceTitle,
                'SETTLEMENT' AS referenceType,
                s.settlement_type AS settlementType,
                NULL AS relatedTransactionStatus,
                FALSE AS resolvedFlag,
                n.created_at AS createdAt
            FROM notification n
            LEFT JOIN settlement s
                ON (
                    n.notification_type = 'SETTLEMENT_PARTICIPANT_ADDED'
                    AND s.settlement_id = n.reference_id
                )
                OR (
                    n.notification_type IN (
                        'SETTLEMENT_DUE_REMINDER_D3',
                        'SETTLEMENT_DUE_REMINDER_D1',
                        'SETTLEMENT_DUE_REMINDER_DDAY'
                    )
                    AND s.settlement_id = n.secondary_reference_id
                )
            WHERE n.user_id = :userId
              AND n.notification_type IN (
                  'SETTLEMENT_PARTICIPANT_ADDED',
                  'SETTLEMENT_DUE_REMINDER_D3',
                  'SETTLEMENT_DUE_REMINDER_D1',
                  'SETTLEMENT_DUE_REMINDER_DDAY'
              )
            ORDER BY n.created_at DESC, n.notification_id DESC
            """, nativeQuery = true)
    List<NotificationResponse> findSettlementNotificationsByUserId(
            @Param("userId") Long userId
    );

    @Query(value = """
            SELECT
                n.notification_id AS notificationId,
                n.notification_type AS notificationType,
                n.title AS title,
                n.content AS content,
                n.reference_id AS referenceId,
                n.secondary_reference_id AS secondaryReferenceId,
                lc.contract_alias AS referenceTitle,
                'CONTRACT' AS referenceType,
                NULL AS settlementType,
                NULL AS relatedTransactionStatus,
                FALSE AS resolvedFlag,
                n.created_at AS createdAt
            FROM notification n
            LEFT JOIN loan_contract lc
                ON lc.contract_id = n.reference_id
            WHERE n.user_id = :userId
              AND n.notification_type IN ('CONTRACT_REQUESTED', 'CONTRACT_CHANGE')
            ORDER BY n.created_at DESC, n.notification_id DESC
            """, nativeQuery = true)
    List<NotificationResponse> findContractNotificationsByUserId(
            @Param("userId") Long userId
    );

    @Query(value = """
            SELECT
                n.notification_id AS notificationId,
                n.notification_type AS notificationType,
                n.title AS title,
                n.content AS content,
                n.reference_id AS referenceId,
                n.secondary_reference_id AS secondaryReferenceId,
                lc_schedule.contract_alias AS referenceTitle,
                'CONTRACT' AS referenceType,
                NULL AS settlementType,
                NULL AS relatedTransactionStatus,
                FALSE AS resolvedFlag,
                n.created_at AS createdAt
            FROM notification n
            LEFT JOIN repayment_schedule rs
                ON rs.schedule_id = n.reference_id
            LEFT JOIN loan_contract lc_schedule
                ON lc_schedule.contract_id = rs.contract_id
            WHERE n.user_id = :userId
              AND n.notification_type IN (
                  'REPAYMENT_DUE_REMINDER_D3',
                  'REPAYMENT_DUE_REMINDER_D1',
                  'REPAYMENT_DUE_REMINDER_DDAY'
              )
            ORDER BY n.created_at DESC, n.notification_id DESC
            """, nativeQuery = true)
    List<NotificationResponse> findRepaymentNotificationsByUserId(
            @Param("userId") Long userId
    );
}
