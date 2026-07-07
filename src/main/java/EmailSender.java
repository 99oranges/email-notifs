import org.jsoup.Jsoup;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles email sending, duplicate detection, and activity logging.
 * Supports per-client SMTP configuration with fallback to defaults.
 */
public class EmailSender {

    private static final Logger LOGGER = Logger.getLogger(EmailSender.class.getName());

    private final Properties config;
    private final Map<String, String> queries;

    public EmailSender(Properties config, Map<String, String> queries) {
        this.config = config;
        this.queries = queries;
    }

    /**
     * Sends an HTML email. Resolves per-client SMTP from client_settings,
     * falls back to default config if not found.
     */
    public boolean sendEmail(Connection conn, String to, String subject, String htmlBody,
                             String from, String cc, String bcc,
                             String tenantId, String clientId) {
        if (to == null || to.isBlank()) {
            LOGGER.warning("No recipient email, skipping");
            return false;
        }

        try {
            // Resolve SMTP session (per-client or default)
            Session session = getSmtpSession(conn, tenantId, clientId);
            String fromAddress = (from != null && !from.isBlank()) ? from : resolveFromEmail(conn, tenantId, clientId);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

            if (cc != null && !cc.isBlank()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
            }
            if (bcc != null && !bcc.isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
            }

            message.setSubject(subject);
            message.setContent(htmlBody, "text/html; charset=utf-8");

            Transport.send(message);
            LOGGER.info("Email sent to: " + to + ", subject: " + subject);
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to send email to " + to + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks if an identical email was already sent (duplicate prevention).
     * Matches on recipient + subject + plain-text body content.
     */
    public boolean emailAlreadySent(Connection conn, String recipients, String subject, String plainTextData) {
        String query = queries.get("Check duplicate");
        if (query == null) {
            LOGGER.warning("Query 'Check duplicate' not found, skipping duplicate check");
            return false;
        }

        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, recipients);
            ps.setString(2, subject);
            ps.setString(3, plainTextData);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Duplicate check failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Logs an activity entry to sch_activity_logs table.
     */
    public void logSchTask(Connection conn, String taskType, boolean success, int retryCount,
                           String data, String recipients, String subject,
                           String tenantId, String clientId) {
        String query = queries.get("Log activity");
        if (query == null) {
            LOGGER.warning("Query 'Log activity' not found, skipping activity log");
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, "SAL-" + UUID.randomUUID().toString().substring(0, 20));
            ps.setString(2, taskType);
            ps.setBoolean(3, success);
            ps.setInt(4, retryCount);
            ps.setString(5, data);
            ps.setString(6, recipients);
            ps.setString(7, subject);
            ps.setString(8, tenantId);
            ps.setString(9, clientId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to log activity: " + e.getMessage(), e);
        }
    }

    /**
     * Converts HTML to plain text using Jsoup (for duplicate comparison).
     */
    public String htmlToPlainText(String html) {
        if (html == null) return "";
        return Jsoup.parse(html).text();
    }

    /**
     * Resolves a JavaMail Session for the given tenant/client.
     * Checks client_settings for custom SMTP; falls back to default config.
     */
    private Session getSmtpSession(Connection conn, String tenantId, String clientId) {
        // Try per-client SMTP from client_settings
        if (tenantId != null && clientId != null) {
            String query = queries.get("Fetch client SMTP settings");
            if (query != null) {
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, clientId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String host = rs.getString("smtp_host");
                            if (host != null && !host.isBlank()) {
                                return buildSession(
                                        host,
                                        rs.getInt("smtp_port") > 0 ? String.valueOf(rs.getInt("smtp_port")) : "587",
                                        rs.getString("smtp_username"),
                                        rs.getString("smtp_password"),
                                        rs.getBoolean("smtp_auth"),
                                        rs.getBoolean("smtp_starttls")
                                );
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to fetch client SMTP settings, using default: " + e.getMessage());
                }
            }
        }

        // Fallback to default config
        return buildSession(
                config.getProperty("mail.smtp.host", "localhost"),
                config.getProperty("mail.smtp.port", "25"),
                config.getProperty("mail.user"),
                config.getProperty("mail.password"),
                Boolean.parseBoolean(config.getProperty("mail.smtp.auth", "false")),
                Boolean.parseBoolean(config.getProperty("mail.smtp.starttls.enable", "false"))
        );
    }

    /**
     * Resolves the from-email: client_settings.smtp_from_email → config mail.from.
     */
    private String resolveFromEmail(Connection conn, String tenantId, String clientId) {
        if (tenantId != null && clientId != null) {
            String query = queries.get("Fetch client SMTP settings");
            if (query != null) {
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, clientId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String fromEmail = rs.getString("smtp_from_email");
                            if (fromEmail != null && !fromEmail.isBlank()) {
                                return fromEmail;
                            }
                        }
                    }
                } catch (Exception e) {
                    // fall through to default
                }
            }
        }
        return config.getProperty("mail.from", "no-reply@eazyops.in");
    }

    private Session buildSession(String host, String port, String username, String password,
                                 boolean auth, boolean starttls) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        if (starttls) {
            props.put("mail.smtp.starttls.required", "true");
        }

        if (auth && username != null && password != null) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        }

        return Session.getInstance(props);
    }
}
