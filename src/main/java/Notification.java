import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone email notification processor for EazySaas.
 * Triggered by Rundeck: java -jar eazysaas-notif.jar TASK_NAME
 *
 * Supported tasks:
 *   PROCESS_EMAIL_QUEUE  - Send all PENDING emails
 *   RETRY_FAILED_EMAILS  - Retry all FAILED emails
 *   PROCESS_ALL          - Both of the above
 */
public class Notification {

    private static final Logger LOGGER = Logger.getLogger(Notification.class.getName());

    // Production: /var/lib/rundeck/eazysaasconf/config.properties
    // Dev fallback: src/main/resources/config.properties
    private static final String CONFIG_PATH = "/var/lib/rundeck/extconfig/config.properties";
    private static final String QUERIES_PATH = "/var/lib/rundeck/extconfig/queries.sql";

    private static final String DEV_CONFIG_PATH = "src/main/resources/config.properties";
    private static final String DEV_QUERIES_PATH = "src/main/resources/queries.sql";

    public static void main(String[] args) {
        if (args.length < 1) {
            LOGGER.severe("No task name provided. Usage: java -jar eazysaas-notif.jar <TASK_NAME>");
            LOGGER.info("Supported tasks: PROCESS_EMAIL_QUEUE, RETRY_FAILED_EMAILS, PROCESS_ALL");
            return;
        }

        String taskName = args[0].toUpperCase();
        LOGGER.info("Starting task: " + taskName);

        try {
            // Load config
            Properties config = loadConfig();
            if (config == null) return;

            // Load queries
            Map<String, String> queries = new HashMap<>();
            if (new java.io.File(QUERIES_PATH).exists()) {
                DatabaseConnector.loadQueries(queries, QUERIES_PATH);
            } else if (new java.io.File(DEV_QUERIES_PATH).exists()) {
                DatabaseConnector.loadQueries(queries, DEV_QUERIES_PATH);
            } else {
                DatabaseConnector.loadQueriesFromClasspath(queries, "queries.sql");
            }

            if (queries.isEmpty()) {
                LOGGER.severe("No queries loaded. Check queries.sql path.");
                return;
            }

            // Create components
            DatabaseConnector dbConnector = new DatabaseConnector(config);
            EmailSender emailSender = new EmailSender(config, queries);
            int maxRetries = Integer.parseInt(config.getProperty("max.retries", "3"));

            // Dispatch task
            switch (taskName) {
                case "PROCESS_EMAIL_QUEUE":
                    processEmailQueue(dbConnector, emailSender, queries, maxRetries);
                    break;
                case "RETRY_FAILED_EMAILS":
                    retryFailedEmails(dbConnector, emailSender, queries, maxRetries);
                    break;
                case "PROCESS_ALL":
                    processEmailQueue(dbConnector, emailSender, queries, maxRetries);
                    retryFailedEmails(dbConnector, emailSender, queries, maxRetries);
                    break;
                default:
                    LOGGER.severe("Unknown task: " + taskName);
                    LOGGER.info("Supported tasks: PROCESS_EMAIL_QUEUE, RETRY_FAILED_EMAILS, PROCESS_ALL");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Task failed: " + e.getMessage(), e);
        }

        LOGGER.info("Task " + taskName + " completed");
    }

    /**
     * Process all PENDING email notifications.
     */
    private static void processEmailQueue(DatabaseConnector dbConnector, EmailSender emailSender,
                                           Map<String, String> queries, int maxRetries) {
        String fetchQuery = queries.get("Fetch pending notifications");
        if (fetchQuery == null) {
            LOGGER.severe("Query 'Fetch pending notifications' not found");
            return;
        }

        try (Connection conn = dbConnector.connect()) {
            if (conn == null) return;

            try (PreparedStatement ps = conn.prepareStatement(fetchQuery)) {
                ps.setInt(1, maxRetries);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        processNotification(conn, rs, emailSender, queries);
                        count++;
                    }
                    if (count > 0) {
                        LOGGER.info("Processed " + count + " pending notifications");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing email queue: " + e.getMessage(), e);
        }
    }

    /**
     * Retry all FAILED email notifications that haven't exceeded max retries.
     */
    private static void retryFailedEmails(DatabaseConnector dbConnector, EmailSender emailSender,
                                           Map<String, String> queries, int maxRetries) {
        String fetchQuery = queries.get("Fetch failed notifications for retry");
        if (fetchQuery == null) {
            LOGGER.severe("Query 'Fetch failed notifications for retry' not found");
            return;
        }

        try (Connection conn = dbConnector.connect()) {
            if (conn == null) return;

            try (PreparedStatement ps = conn.prepareStatement(fetchQuery)) {
                ps.setInt(1, maxRetries);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        processNotification(conn, rs, emailSender, queries);
                        count++;
                    }
                    if (count > 0) {
                        LOGGER.info("Retried " + count + " failed notifications");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error retrying failed emails: " + e.getMessage(), e);
        }
    }

    /**
     * Process a single notification row: duplicate check → send → update status → log.
     */
    private static void processNotification(Connection conn, ResultSet rs, EmailSender emailSender,
                                             Map<String, String> queries) {
        try {
            String id = rs.getString("id");
            String tenantId = rs.getString("tenant_id");
            String clientId = rs.getString("client_id");
            String subject = rs.getString("email_subject");
            String htmlBody = rs.getString("email_body");
            String fromEmail = rs.getString("from_email_address");
            String toEmail = rs.getString("to_email_address");
            String ccEmail = rs.getString("cc_email_address");
            String bccEmail = rs.getString("bcc_email_address");
            int retryCount = rs.getInt("retry_count");

            String plainText = emailSender.htmlToPlainText(htmlBody);

            // Duplicate check
            if (emailSender.emailAlreadySent(conn, toEmail, subject, plainText)) {
                LOGGER.info("Skipping duplicate email to " + toEmail + ", subject: " + subject);
                markAsSent(conn, queries, id);
                return;
            }

            // Send email
            boolean success = emailSender.sendEmail(conn, toEmail, subject, htmlBody,
                    fromEmail, ccEmail, bccEmail, tenantId, clientId);

            if (success) {
                markAsSent(conn, queries, id);
                emailSender.logSchTask(conn, "Email Notification", true, retryCount,
                        plainText, toEmail, subject, tenantId, clientId);
            } else {
                int newRetryCount = retryCount + 1;
                markAsFailed(conn, queries, id, newRetryCount, "Email sending failed");
                emailSender.logSchTask(conn, "Email Notification", false, newRetryCount,
                        plainText, toEmail, subject, tenantId, clientId);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing notification: " + e.getMessage(), e);
            try {
                String id = rs.getString("id");
                int retryCount = rs.getInt("retry_count");
                markAsFailed(conn, queries, id, retryCount + 1, e.getMessage());
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to mark notification as failed: " + ex.getMessage());
            }
        }
    }

    private static void markAsSent(Connection conn, Map<String, String> queries, String id) {
        String query = queries.get("Mark notification as sent");
        if (query == null) return;
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark notification as sent: " + e.getMessage());
        }
    }

    private static void markAsFailed(Connection conn, Map<String, String> queries,
                                      String id, int retryCount, String errorMessage) {
        String query = queries.get("Mark notification as failed");
        if (query == null) return;
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setInt(1, retryCount);
            ps.setString(2, errorMessage != null ? errorMessage.substring(0, Math.min(errorMessage.length(), 1000)) : null);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark notification as failed: " + e.getMessage());
        }
    }

    private static Properties loadConfig() {
        Properties config = new Properties();

        // Try production path first
        if (new java.io.File(CONFIG_PATH).exists()) {
            try (FileInputStream fis = new FileInputStream(CONFIG_PATH)) {
                config.load(fis);
                LOGGER.info("Config loaded from: " + CONFIG_PATH);
                return config;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load config from " + CONFIG_PATH, e);
            }
        }

        // Try dev filesystem path
        if (new java.io.File(DEV_CONFIG_PATH).exists()) {
            try (FileInputStream fis = new FileInputStream(DEV_CONFIG_PATH)) {
                config.load(fis);
                LOGGER.info("Config loaded from: " + DEV_CONFIG_PATH);
                return config;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load config from " + DEV_CONFIG_PATH, e);
            }
        }

        // Fallback: load from classpath (inside JAR)
        try (java.io.InputStream is = Notification.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                config.load(is);
                LOGGER.info("Config loaded from classpath (inside JAR)");
                return config;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load config from classpath", e);
        }

        LOGGER.severe("Could not load config.properties from any location");
        return null;
    }
}
