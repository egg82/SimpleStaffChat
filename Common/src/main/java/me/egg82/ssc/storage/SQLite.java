package me.egg82.ssc.storage;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.zaxxer.hikari.HikariConfig;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import me.egg82.ssc.core.ChatResult;
import me.egg82.ssc.core.PostChatResult;
import me.egg82.ssc.services.StorageHandler;
import me.egg82.ssc.utils.ValidationUtil;
import ninja.egg82.core.SQLExecuteResult;
import ninja.egg82.core.SQLQueryResult;
import ninja.egg82.sql.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;

public class SQLite extends AbstractSQL {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final LoadingCache<UUID, Long> longPlayerIDCache = Caffeine.newBuilder().build(this::getLongPlayerIDExpensive);

    private String serverName;
    private String serverID;
    private UUID uuidServerID;
    private long longServerID;
    private volatile long lastMessageID;
    private StorageHandler handler;

    private SQLite() { }

    public static SQLite.Builder builder(UUID serverID, String serverName, StorageHandler handler) { return new SQLite.Builder(serverID, serverName, handler); }

    public static class Builder {
        private final SQLite result = new SQLite();
        private final HikariConfig config = new HikariConfig();

        private Builder(UUID serverID, String serverName, StorageHandler handler) {
            if (serverID == null) {
                throw new IllegalArgumentException("serverID cannot be null.");
            }
            if (serverName == null) {
                throw new IllegalArgumentException("serverName cannot be null.");
            }
            if (handler == null) {
                throw new IllegalArgumentException("handler cannot be null.");
            }

            result.uuidServerID = serverID;
            result.serverID = serverID.toString();
            result.serverName = serverName;
            result.handler = handler;

            // Baseline
            config.setConnectionTestQuery("SELECT 1;");
            config.setAutoCommit(true);
            config.addDataSourceProperty("useLegacyDatetimeCode", "false");
            config.addDataSourceProperty("serverTimezone", "UTC");
        }

        public SQLite.Builder file(File file, String prefix) {
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            result.database = "";
            result.prefix = prefix;
            return this;
        }

        public SQLite.Builder options(String options) throws IOException {
            options = options.charAt(0) == '?' ? options.substring(1) : options;
            Properties p = new Properties();
            p.load(new StringReader(options.replace("&", "\n")));
            config.setDataSourceProperties(p);
            return this;
        }

        public SQLite.Builder poolSize(int min, int max) {
            config.setMaximumPoolSize(max);
            config.setMinimumIdle(min);
            return this;
        }

        public SQLite.Builder life(long lifetime, long timeout) {
            config.setMaxLifetime(lifetime);
            config.setConnectionTimeout(timeout);
            return this;
        }

        public SQLite build() throws IOException, SQLException, StorageException {
            result.sql = new SQL(config);
            SQLVersionUtil.conformVersion(result, "mysql");
            result.setServerName(result.serverName);
            result.longServerID = getLongServerID();
            result.lastMessageID = getLastMessageID();
            return result;
        }

        private long getLongServerID() throws SQLException {
            SQLQueryResult r = result.sql.query("SELECT `id` FROM `" + result.prefix + "servers` WHERE `uuid`=?;", result.serverID);
            if (r.getData().length != 1) {
                throw new SQLException("Could not get server ID.");
            }
            return ((Number) r.getData()[0][0]).longValue();
        }

        private long getLastMessageID() throws SQLException {
            SQLQueryResult r = result.sql.query("SELECT MAX(`id`) FROM `" + result.prefix + "posted_chat`;");
            if (r.getData().length != 1) {
                throw new SQLException("Could not get message IDs.");
            }
            return r.getData()[0][0] != null ? ((Number) r.getData()[0][0]).longValue() : 0;
        }
    }

    public Set<ChatResult> getQueue() throws StorageException {
        Set<ChatResult> retVal = new LinkedHashSet<>();
        SQLQueryResult result;
        try {
            result = sql.call(
                    "SELECT" +
                        "  `c`.`id`," +
                        "  `s`.`uuid` AS `server_id`," +
                        "  `s`.`name` AS `server_name`," +
                        "  `p`.`uuid` AS `player_id`," +
                        "  `c`.`level`," +
                        "  `l`.`name` AS `level_name`," +
                        "  `c`.`message`," +
                        "  `c`.`date`" +
                        "FROM `{prefix}posted_chat` `c`" +
                        "JOIN `{prefix}servers` `s` ON `s`.`id` = `c`.`server_id`" +
                        "JOIN `{prefix}players` `p` ON `p`.`id` = `c`.`player_id`" +
                        "JOIN `{prefix}levels` `l` ON `l`.`id` = `c`.`level`" +
                        "WHERE ? <> `c`.`server_id` AND `c`.`id` > ?;",
                    serverID, lastMessageID);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        for (Object[] row : result.getData()) {
            ChatResult r = getResult(row);
            if (r != null) {
                lastMessageID = r.getID();
                retVal.add(r);
            }
        }
        return retVal;
    }

    public Set<ChatResult> getByPlayer(UUID playerID, int days) throws StorageException {
        if (playerID == null) {
            throw new IllegalArgumentException("playerID cannot be null.");
        }

        long longPlayerID = longPlayerIDCache.get(playerID);
        Set<ChatResult> retVal = new LinkedHashSet<>();
        SQLQueryResult result;
        try {
            result = sql.call(
                    "SELECT" +
                        "  `c`.`id`," +
                        "  `s`.`uuid` AS `server_id`," +
                        "  `s`.`name` AS `server_name`," +
                        "  `p`.`uuid` AS `player_id`," +
                        "  `c`.`level`," +
                        "  `l`.`name` AS `level_name`," +
                        "  `c`.`message`," +
                        "  `c`.`date`" +
                        "FROM `{prefix}posted_chat` `c`" +
                        "JOIN `{prefix}servers` `s` ON `s`.`id` = `c`.`server_id`" +
                        "JOIN `{prefix}players` `p` ON `p`.`id` = `c`.`player_id`" +
                        "JOIN `{prefix}levels` `l` ON `l`.`id` = `c`.`level`" +
                        "WHERE `c`.`date` >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY) AND `c`.`player_id` = ?;",
                    days, longPlayerID);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        for (Object[] row : result.getData()) {
            retVal.add(getResult(row));
        }
        retVal.remove(null);
        return retVal;
    }

    public PostChatResult post(UUID playerID, byte level, String message) throws StorageException {
        if (playerID == null) {
            throw new IllegalArgumentException("playerID cannot be null.");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null.");
        }

        long longPlayerID = longPlayerIDCache.get(playerID);
        SQLExecuteResult result;
        try {
            result = sql.execute("INSERT INTO `" + prefix + "posted_chat` (`server_id`, `player_id`, `level`, `message`) VALUES (?, ?, ?, ?);", longServerID, longPlayerID, level, message);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        if (result.getAutoGeneratedKeys().length != 1) {
            throw new StorageException(false, "Could not get generated keys from inserted post.");
        }

        return new PostChatResult(
                ((Number) result.getAutoGeneratedKeys()[0]).longValue(),
                longServerID,
                longPlayerID,
                level,
                message,
                getTime(result.getAutoGeneratedKeys()[1]).getTime()
        );
    }

    public void setLevelRaw(byte level, String name) throws StorageException {
        try {
            sql.execute("INSERT OR REPLACE INTO `" + prefix + "levels` (`id`, `name`) VALUES (?, ?);", level, name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setServerRaw(long longServerID, UUID serverID, String name) throws StorageException {
        try {
            sql.execute("INSERT OR REPLACE INTO `" + prefix + "servers` (`id`, `uuid`, `name`) VALUES (?, ?, ?);", longServerID, serverID.toString(), name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setPlayerRaw(long longPlayerID, UUID playerID) throws StorageException {
        try {
            sql.execute("INSERT OR REPLACE INTO `" + prefix + "players` (`id`, `uuid`) VALUES (?, ?);", longServerID, playerID.toString());
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        longPlayerIDCache.put(playerID, longPlayerID);
    }

    public void postRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws StorageException {
        try {
            sql.execute("INSERT OR IGNORE INTO `" + prefix + "posted_chat` (`id`, `server_id`, `player_id`, `level`, `message`, `date`) VALUES (?, ?, ?, ?, ?, ?);", postID, longServerID, longPlayerID, level, message, date);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setLevel(byte level, String name) throws StorageException {
        try {
            sql.execute("INSERT OR REPLACE INTO `" + prefix + "levels` (`level`, `name`) VALUES (?, ?);", serverID, name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setServerName(String name) throws StorageException {
        try {
            sql.execute("INSERT OR REPLACE INTO `" + prefix + "servers` (`uuid`, `name`) VALUES (?, ?);", serverID, name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    protected void setKey(String key, String value) throws SQLException { sql.execute("INSERT OR REPLACE INTO `" + prefix + "data` (`key`, `value`) VALUES (?, ?);", key, value); }

    protected double getDouble(String key) throws SQLException {
        SQLQueryResult result = sql.query("SELECT `value` FROM `" + prefix + "data` WHERE `key`=?;", key);
        if (result.getData().length == 1) {
            return Double.parseDouble((String) result.getData()[0][0]);
        }
        return -1.0d;
    }

    public long getLongPlayerID(UUID playerID) { return longPlayerIDCache.get(playerID); }

    private ChatResult getResult(Object[] row) {
        String serverID = (String) row[1];
        String playerID = (String) row[3];
        if (!ValidationUtil.isValidUuid(serverID)) {
            logger.warn("Chat ID " + row[1] + " has an invalid server ID.");
            return null;
        }
        if (!ValidationUtil.isValidUuid(playerID)) {
            logger.warn("Chat ID " + row[3] + " has an invalid player ID.");
            return null;
        }

        return new ChatResult(
                ((Number) row[0]).longValue(),
                UUID.fromString(serverID),
                (String) row[2],
                UUID.fromString(playerID),
                ((Number) row[4]).byteValue(),
                (String) row[5],
                (String) row[6],
                getTime(row[7]).getTime()
        );
    }

    private long getLongPlayerIDExpensive(UUID uuid) throws SQLException {
        // A majority of the time there'll be an ID
        SQLQueryResult result = sql.query("SELECT `id` FROM `" + prefix + "players` WHERE `uuid`=?;", uuid.toString());
        if (result.getData().length == 1) {
            return ((Number) result.getData()[0][0]).longValue();
        }

        // No ID, generate one
        SQLExecuteResult r = sql.execute("INSERT INTO `" + prefix + "players` (`uuid`) VALUES (?);", uuid.toString());
        if (r.getAutoGeneratedKeys().length != 1) {
            throw new SQLException("Could not get generated keys from inserted player.");
        }
        long id = ((Number) r.getAutoGeneratedKeys()[0]).longValue();
        handler.playerIDCreationCallback(uuid, id, this);
        return id;
    }

    private Timestamp getTime(Object o) {
        if (o instanceof String) {
            return Timestamp.valueOf((String) o);
        } else if (o instanceof Number) {
            return new Timestamp(((Number) o).longValue());
        }
        logger.warn("Could not parse time.");
        return new Timestamp(0L);
    }

    private boolean isAutomaticallyRecoverable(SQLException ex) {
        if (
                ex.getErrorCode() == SQLiteErrorCode.SQLITE_BUSY.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_LOCKED.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_NOMEM.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_BUSY_RECOVERY.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT.code
                || ex.getErrorCode() == SQLiteErrorCode.SQLITE_IOERR_NOMEM.code
        ) {
            return true;
        }
        return false;
    }
}
