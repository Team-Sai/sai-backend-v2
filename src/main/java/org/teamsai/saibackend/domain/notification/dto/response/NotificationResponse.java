package org.teamsai.saibackend.domain.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
    Long getResolvedFlag();

    default boolean isResolved() {
        Long resolvedFlag = getResolvedFlag();
        return resolvedFlag != null && resolvedFlag == 1L;
    }

    LocalDateTime getCreatedAt();
}
