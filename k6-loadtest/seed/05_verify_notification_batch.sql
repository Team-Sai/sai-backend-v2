-- 1회 실행 후 및 동일 기준일 재실행 후 모두 실행한다. 위반 쿼리는 0행이어야 한다.
-- 상환 연체/예정일 분류 검증 (D-2 및 PAID 음성 대조군 포함).
SELECT e.schedule_id, e.bucket, r.status, e.expected_status, r.due_date, e.due_date AS expected_date
FROM lt_schedule_expectation e LEFT JOIN repayment_schedule r ON r.schedule_id = e.schedule_id
WHERE r.schedule_id IS NULL OR r.status <> e.expected_status OR r.due_date <> e.due_date;
SELECT e.payment_obligation_id, po.overdue_since, e.expected_overdue_since
FROM lt_obligation_expectation e LEFT JOIN payment_obligation po ON po.payment_obligation_id = e.payment_obligation_id
WHERE po.payment_obligation_id IS NULL OR NOT (po.overdue_since <=> e.expected_overdue_since);

-- 수신자 + 알림 종류 + 대상 ID로 정확히 한 건이어야 한다. 누락 및 중복 모두 검출.
SELECT 'repayment' AS kind, e.schedule_id AS target_id, e.user_id, e.expected_notification, COUNT(n.notification_id) AS actual
FROM lt_schedule_expectation e LEFT JOIN notification n ON n.reference_id = e.schedule_id
 AND n.user_id = e.user_id AND n.notification_type = e.expected_notification
WHERE e.expected_notification IS NOT NULL
GROUP BY e.schedule_id, e.user_id, e.expected_notification HAVING COUNT(n.notification_id) <> 1
UNION ALL
SELECT 'settlement', e.payment_obligation_id, e.user_id, e.expected_notification, COUNT(n.notification_id)
FROM lt_obligation_expectation e LEFT JOIN notification n ON n.reference_id = e.payment_obligation_id
 AND n.user_id = e.user_id AND n.notification_type = e.expected_notification
WHERE e.expected_notification IS NOT NULL
GROUP BY e.payment_obligation_id, e.user_id, e.expected_notification HAVING COUNT(n.notification_id) <> 1;

-- 잘못된 예정일 단계/수신자/제외 대상 발송/보조 대상 참조 검출.
SELECT n.notification_id, n.user_id, n.notification_type, n.reference_id
FROM notification n JOIN lt_schedule_expectation e ON e.schedule_id = n.reference_id
WHERE n.notification_type LIKE 'REPAYMENT_DUE_REMINDER_%'
 AND (e.expected_notification IS NULL OR n.notification_type <> e.expected_notification
      OR n.user_id <> e.user_id OR n.secondary_reference_id IS NOT NULL)
UNION ALL
SELECT n.notification_id, n.user_id, n.notification_type, n.reference_id
FROM notification n JOIN lt_obligation_expectation e ON e.payment_obligation_id = n.reference_id
WHERE n.notification_type LIKE 'SETTLEMENT_DUE_REMINDER_%'
 AND (e.expected_notification IS NULL OR n.notification_type <> e.expected_notification
      OR n.user_id <> e.user_id OR NOT (n.secondary_reference_id <=> e.settlement_id));

-- 실제 저장 건수: Job writeCount와 구분해서 비교한다.
SELECT n.notification_type, COUNT(*) AS stored_notifications
FROM notification n WHERE
 (n.notification_type LIKE 'REPAYMENT_DUE_REMINDER_%' AND n.reference_id IN (SELECT schedule_id FROM lt_schedule_expectation))
 OR (n.notification_type LIKE 'SETTLEMENT_DUE_REMINDER_%' AND n.reference_id IN (SELECT payment_obligation_id FROM lt_obligation_expectation))
GROUP BY n.notification_type;
