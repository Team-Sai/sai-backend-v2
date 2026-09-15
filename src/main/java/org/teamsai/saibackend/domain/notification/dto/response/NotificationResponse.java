package org.teamsai.saibackend.domain.notification.dto.response;

import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;

import java.time.LocalDateTime;

public interface NotificationResponse {

    Long getNotificationId();

    NotificationType getNotificationType();

    String getTitle();

    String getContent();

    Long getReferenceId();

    Long getSecondaryReferenceId();

    String getReferenceTitle();

    String getReferenceType();

    String getSettlementType();

    BankTransactionProcessingStatus getRelatedTransactionStatus();

    boolean isResolved();

    LocalDateTime getCreatedAt();
}
