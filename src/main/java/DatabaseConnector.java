import java.io.*;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles database connections and SQL query file loading.
 * Uses pure JDBC (no Spring/JPA) for standalone JAR execution.
 */
public class DatabaseConnector {

    private static final Logger LOGGER = Logger.getLogger(DatabaseConnector.class.getName());

    private final Properties properties;

    public DatabaseConnector(Properties properties) {
        this.properties = properties;
    }

    /**
     * Creates a new JDBC connection using config.properties credentials.
     */
    public Connection connect() {
        try {
            return DriverManager.getConnection(
                    properties.getProperty("db.url"),
                    properties.getProperty("db.user"),
                    properties.getProperty("db.password")
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to connect to database: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Loads SQL queries from queries.sql file into a Map.
     * Format: Lines starting with # are query keys, followed by SQL until ;
     *
     * Example:
     *   # Fetch pending notifications
     *   SELECT * FROM email_notifications WHERE status = 'PENDING';
     */
    public static void loadQueries(Map<String, String> queries, String queriesPath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(queriesPath))) {
            String line;
            String currentKey = null;
            StringBuilder currentQuery = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#")) {
                    // Save previous query if exists
                    if (currentKey != null && currentQuery.length() > 0) {
                        queries.put(currentKey, currentQuery.toString().trim());
                    }
                    currentKey = line.substring(1).trim();
                    currentQuery = new StringBuilder();
                } else {
                    if (currentQuery.length() > 0) {
                        currentQuery.append(" ");
                    }
                    // Remove trailing semicolons for PreparedStatement compatibility
                    if (line.endsWith(";")) {
                        currentQuery.append(line, 0, line.length() - 1);
                    } else {
                        currentQuery.append(line);
                    }
                }
            }

            // Save last query
            if (currentKey != null && currentQuery.length() > 0) {
                queries.put(currentKey, currentQuery.toString().trim());
            }

            LOGGER.info("Loaded " + queries.size() + " queries from " + queriesPath);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load queries from " + queriesPath, e);
        }
    }

    /**
     * Loads queries from classpath (inside JAR).
     */
    public static void loadQueriesFromClasspath(Map<String, String> queries, String resourceName) {
        try (InputStream is = DatabaseConnector.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                LOGGER.severe("Resource not found in classpath: " + resourceName);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                String currentKey = null;
                StringBuilder currentQuery = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("#")) {
                        if (currentKey != null && currentQuery.length() > 0) {
                            queries.put(currentKey, currentQuery.toString().trim());
                        }
                        currentKey = line.substring(1).trim();
                        currentQuery = new StringBuilder();
                    } else {
                        if (currentQuery.length() > 0) currentQuery.append(" ");
                        if (line.endsWith(";")) {
                            currentQuery.append(line, 0, line.length() - 1);
                        } else {
                            currentQuery.append(line);
                        }
                    }
                }

                if (currentKey != null && currentQuery.length() > 0) {
                    queries.put(currentKey, currentQuery.toString().trim());
                }

                LOGGER.info("Loaded " + queries.size() + " queries from classpath: " + resourceName);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load queries from classpath: " + resourceName, e);
        }
    }
}
