# Fetch pending notifications
SELECT id, tenant_id, client_id, email_subject, email_body,
       from_email_address, to_email_address, cc_email_address, bcc_email_address,
       retry_count
FROM email_notifications
WHERE notification_status = 'PENDING' AND retry_count < ? AND record_status = 1;

# Fetch failed notifications for retry
SELECT id, tenant_id, client_id, email_subject, email_body,
       from_email_address, to_email_address, cc_email_address, bcc_email_address,
       retry_count
FROM email_notifications
WHERE notification_status = 'FAILED' AND retry_count < ? AND record_status = 1;

# Mark notification as sent
UPDATE email_notifications
SET notification_status = 'SENT', sent_at = NOW(), error_message = NULL, updated_at = NOW()
WHERE id = ?;

# Mark notification as failed
UPDATE email_notifications
SET notification_status = 'FAILED', retry_count = ?, error_message = ?, updated_at = NOW()
WHERE id = ?;

# Check duplicate
SELECT 1 FROM sch_activity_logs
WHERE sch_recipients = ? AND sch_sub = ? AND sch_data = ? AND sch_task_status = true
LIMIT 1;

# Log activity
INSERT INTO sch_activity_logs
(id, sch_task_type, sch_task_status, sch_task_retry_count, sch_data, sch_recipients, sch_sub, tenant_id, client_id, record_status, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, NOW(), NOW());

# Fetch client SMTP settings
SELECT smtp_host, smtp_port, smtp_username, smtp_password, smtp_from_email, smtp_auth, smtp_starttls
FROM client_settings
WHERE tenant_id = ? AND client_id = ? AND record_status = 1;
