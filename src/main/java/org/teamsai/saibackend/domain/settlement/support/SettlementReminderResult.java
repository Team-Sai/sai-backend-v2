package org.teamsai.saibackend.domain.settlement.support;

/**
 * processedCount: 처리한 알림 수, failedCount: 정산 조회/처리 실패 수,
 * failedNotificationCount: 개별 알림 저장 실패 수.
 */
public record SettlementReminderResult(int processedCount, int failedCount, int failedNotificationCount) {
}
