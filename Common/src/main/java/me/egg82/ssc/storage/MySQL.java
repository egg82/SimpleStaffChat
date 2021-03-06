package me.egg82.ssc.storage;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.egg82.ssc.core.*;
import me.egg82.ssc.services.StorageHandler;
import me.egg82.ssc.utils.ValidationUtil;
import ninja.egg82.core.SQLExecuteResult;
import ninja.egg82.core.SQLQueryResult;
import ninja.egg82.sql.SQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MySQL extends AbstractSQL {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Object levelCacheLock = new Object();
    private volatile long lastLevelCacheTime = 0L;
    private final Set<LevelResult> tmpLevelCache = new LinkedHashSet<>();

    private final LoadingCache<Byte, String> levelCache = Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.MINUTES).expireAfterWrite(30L, TimeUnit.SECONDS).build(this::getLevelExpensive);
    private final LoadingCache<UUID, Long> longPlayerIDCache = Caffeine.newBuilder().build(this::getLongPlayerIDExpensive);

    private String serverName;
    private String serverID;
    private UUID uuidServerID;
    private long longServerID;
    private volatile long lastMessageID;
    private StorageHandler handler;

    private MySQL() { }

    private volatile boolean closed = false;

    public void close() {
        closed = true;
        sql.close();
    }

    public boolean isClosed() { return closed || sql.isClosed(); }

    public static MySQL.Builder builder(UUID serverID, String serverName, StorageHandler handler) { return new MySQL.Builder(serverID, serverName, handler); }

    public static class Builder {
        private final MySQL result = new MySQL();
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
            config.setPoolName("SimpleStaffChat-MySQL");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setConnectionTestQuery("SELECT 1;");
            config.setAutoCommit(true);
            config.addDataSourceProperty("useLegacyDatetimeCode", "false");
            config.addDataSourceProperty("serverTimezone", "UTC");

            // Optimizations
            // http://assets.en.oreilly.com/1/event/21/Connector_J%20Performance%20Gems%20Presentation.pdf
            // https://webcache.googleusercontent.com/search?q=cache:GqZCOIZxeK0J:assets.en.oreilly.com/1/event/21/Connector_J%2520Performance%2520Gems%2520Presentation.pdf+&cd=1&hl=en&ct=clnk&gl=us
            config.addDataSourceProperty("cacheServerConfiguration", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("useLocalTransactionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("maintainTimeStats", "false");
            config.addDataSourceProperty("useUnbufferedIO", "false");
            config.addDataSourceProperty("useReadAheadInput", "false");
            // https://github.com/brettwooldridge/HikariCP/wiki/MySQL-Configuration
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("cacheResultSetMetadata", "true");
            config.addDataSourceProperty("elideSetAutoCommits", "true");
        }

        public MySQL.Builder url(String address, int port, String database, String prefix) {
            config.setJdbcUrl("jdbc:mysql://" + address + ":" + port + "/" + database);
            result.database = database;
            result.prefix = prefix;
            return this;
        }

        public MySQL.Builder credentials(String user, String pass) {
            config.setUsername(user);
            config.setPassword(pass);
            return this;
        }

        public MySQL.Builder options(String options) throws IOException {
            options = !options.isEmpty() && options.charAt(0) == '?' ? options.substring(1) : options;
            Properties p = new Properties();
            p.load(new StringReader(options.replace("&", "\n")));
            config.setDataSourceProperties(p);
            return this;
        }

        public MySQL.Builder poolSize(int min, int max) {
            config.setMaximumPoolSize(max);
            config.setMinimumIdle(min);
            return this;
        }

        public MySQL.Builder life(long lifetime, long timeout) {
            config.setMaxLifetime(lifetime);
            config.setConnectionTimeout(timeout);
            return this;
        }

        public MySQL build() throws IOException, StorageException {
            result.sql = new SQL(config);
            SQLVersionUtil.conformVersion(result, "mysql");
            result.setServerName(result.serverName);
            result.longServerID = getLongServerID();
            result.lastMessageID = getLastMessageID();
            return result;
        }

        private long getLongServerID() throws StorageException {
            SQLQueryResult r;
            try {
                r = result.sql.query("SELECT `id` FROM `" + result.prefix + "servers` WHERE `uuid`=?;", result.serverID);
            } catch (SQLException ex) {
                throw new StorageException(false, ex);
            }
            if (r.getData().length != 1) {
                throw new StorageException(false, "Could not get server ID.");
            }
            return ((Number) r.getData()[0][0]).longValue();
        }

        private long getLastMessageID() throws StorageException {
            SQLQueryResult r;
            try {
                r = result.sql.query("SELECT MAX(`id`) FROM `" + result.prefix + "posted_chat`;");
            } catch (SQLException ex) {
                throw new StorageException(false, ex);
            }
            if (r.getData().length != 1) {
                throw new StorageException(false, "Could not get message IDs.");
            }
            return r.getData()[0][0] != null ? ((Number) r.getData()[0][0]).longValue() : 0;
        }
    }

    public ImmutableList<LevelResult> getLevels() throws StorageException {
        if (lastLevelCacheTime <= System.currentTimeMillis() - 300000L) { // 5 mins
            synchronized (levelCacheLock) {
                tmpLevelCache.clear();
                try {
                    tmpLevelCache.addAll(fetchLevelsExpensive());
                } catch (SQLException ex) {
                    throw new StorageException(isAutomaticallyRecoverable(ex), "Could not get levels.");
                }
                lastLevelCacheTime = System.currentTimeMillis();
            }
        }
        return ImmutableList.copyOf(tmpLevelCache);
    }

    private List<LevelResult> fetchLevelsExpensive() throws SQLException {
        List<LevelResult> retVal = new ArrayList<>();
        SQLQueryResult result = sql.query("SELECT `id`, `name` FROM `" + prefix + "levels`;");
        for (Object[] row : result.getData()) {
            retVal.add(new LevelResult(
                    ((Number) row[0]).byteValue(),
                    (String) row[1]
            ));
        }
        return retVal;
    }

    public Set<ChatResult> getQueue() throws StorageException {
        Set<ChatResult> retVal = new LinkedHashSet<>();
        SQLQueryResult result;
        try {
            result = sql.call("call `" + prefix + "get_queue_id`(?, ?);", lastMessageID, longServerID);
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
            result = sql.call("call `" + prefix + "get_messages_player`(?, ?);", longPlayerID, days);
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

        long id = ((Number) result.getAutoGeneratedKeys()[0]).longValue();

        SQLQueryResult query;
        try {
            query = sql.query("SELECT `date` FROM `" + prefix + "posted_chat` WHERE `id`=?;", id);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        if (query.getData().length != 1) {
            throw new StorageException(false, "Could not get date from inserted post.");
        }

        return new PostChatResult(
                id,
                longServerID,
                uuidServerID,
                serverName,
                longPlayerID,
                playerID,
                level,
                levelCache.get(level),
                message,
                ((Timestamp) query.getData()[0][0]).getTime()
        );
    }

    public void setLevelRaw(byte level, String name) throws StorageException {
        try {
            sql.execute("INSERT INTO `" + prefix + "levels` (`id`, `name`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `name`=?;", level, name, name);
            levelCache.put(level, name);
            LevelResult l = new LevelResult(level, name);
            tmpLevelCache.remove(l);
            tmpLevelCache.add(l);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setServerRaw(long longServerID, UUID serverID, String name) throws StorageException {
        try {
            sql.execute("INSERT INTO `" + prefix + "servers` (`id`, `uuid`, `name`) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `id`=?, `uuid`=?, `name`=?;", longServerID, serverID.toString(), name, longServerID, serverID.toString(), name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setPlayerRaw(long longPlayerID, UUID playerID) throws StorageException {
        try {
            sql.execute("INSERT INTO `" + prefix + "players` (`id`, `uuid`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `id`=?, `uuid`=?;", longPlayerID, playerID.toString(), longPlayerID, playerID.toString());
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
        longPlayerIDCache.put(playerID, longPlayerID);
    }

    public void postRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws StorageException {
        try {
            sql.execute("INSERT IGNORE INTO `" + prefix + "posted_chat` (`id`, `server_id`, `player_id`, `level`, `message`, `date`) VALUES (?, ?, ?, ?, ?, ?);", postID, longServerID, longPlayerID, level, message, new Timestamp(date));
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setLevel(byte level, String name) throws StorageException {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null.");
        }
        setLevelRaw(level, name);
    }

    public void setServerName(String name) throws StorageException {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null.");
        }
        // Don't redirect to raw. Will cause issues when server is first added
        try {
            sql.execute("INSERT INTO `" + prefix + "servers` (`uuid`, `name`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `name`=?;", serverID, name, name);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    protected void setKey(String key, String value) throws SQLException { sql.execute("INSERT INTO `" + prefix + "data` (`key`, `value`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `value`=?;", key, value, value); }

    protected double getDouble(String key) throws SQLException {
        SQLQueryResult result = sql.query("SELECT `value` FROM `" + prefix + "data` WHERE `key`=?;", key);
        if (result.getData().length == 1) {
            return Double.parseDouble((String) result.getData()[0][0]);
        }
        return -1.0d;
    }

    public long getLongPlayerID(UUID playerID) { return longPlayerIDCache.get(playerID); }

    public Set<LevelResult> dumpLevels() throws StorageException {
        Set<LevelResult> retVal = new LinkedHashSet<>();

        SQLQueryResult result;
        try {
            result = sql.query("SELECT `id`, `name` FROM `" + prefix + "levels`;");
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }

        for (Object[] row : result.getData()) {
            retVal.add(new LevelResult(
                    ((Number) row[0]).byteValue(),
                    (String) row[1]
            ));
        }

        return retVal;
    }

    public void loadLevels(Set<LevelResult> levels) throws StorageException {
        // TODO: Batch execute
        try {
            sql.execute("SET FOREIGN_KEY_CHECKS = 0;");
            sql.execute("TRUNCATE `" + prefix + "levels`;");
            levelCache.invalidateAll();
            for (LevelResult level : levels) {
                sql.execute("INSERT INTO `" + prefix + "levels` (`id`, `name`) VALUES (?, ?);", level.getLevel(), level.getName());
                levelCache.put(level.getLevel(), level.getName());
            }
            sql.execute("SET FOREIGN_KEY_CHECKS = 1;");
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<ServerResult> dumpServers() throws StorageException {
        Set<ServerResult> retVal = new LinkedHashSet<>();

        SQLQueryResult result;
        try {
            result = sql.query("SELECT `id`, `uuid`, `name` FROM `" + prefix + "servers`;");
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }

        for (Object[] row : result.getData()) {
            String sid = (String) row[1];
            if (!ValidationUtil.isValidUuid(sid)) {
                logger.warn("Server ID " + ((Number) row[0]).longValue() + " has an invalid UUID \"" + sid + "\".");
                continue;
            }

            retVal.add(new ServerResult(
                    ((Number) row[0]).longValue(),
                    UUID.fromString(sid),
                    (String) row[2]
            ));
        }

        return retVal;
    }

    public void loadServers(Set<ServerResult> servers) throws StorageException {
        // TODO: Batch execute
        try {
            sql.execute("SET FOREIGN_KEY_CHECKS = 0;");
            sql.execute("TRUNCATE `" + prefix + "servers`;");
            for (ServerResult server : servers) {
                sql.execute("INSERT INTO `" + prefix + "servers` (`id`, `uuid`, `name`) VALUES (?, ?, ?);", server.getLongServerID(), server.getServerID().toString(), server.getName());
            }
            sql.execute("SET FOREIGN_KEY_CHECKS = 1;");
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<PlayerResult> dumpPlayers(long begin, int size) throws StorageException {
        Set<PlayerResult> retVal = new LinkedHashSet<>();

        SQLQueryResult result;
        try {
            result = sql.query("SELECT `id`, `uuid` FROM `" + prefix + "players` LIMIT ?, ?;", begin - 1, size);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }

        for (Object[] row : result.getData()) {
            String pid = (String) row[1];
            if (!ValidationUtil.isValidUuid(pid)) {
                logger.warn("Player ID " + ((Number) row[0]).longValue() + " has an invalid UUID \"" + pid + "\".");
                continue;
            }

            retVal.add(new PlayerResult(
                    ((Number) row[0]).longValue(),
                    UUID.fromString(pid)
            ));
        }

        return retVal;
    }

    public void loadPlayers(Set<PlayerResult> players, boolean truncate) throws StorageException {
        // TODO: Batch execute
        try {
            if (truncate) {
                sql.execute("SET FOREIGN_KEY_CHECKS = 0;");
                sql.execute("TRUNCATE `" + prefix + "players`;");
                longPlayerIDCache.invalidateAll();
            }
            for (PlayerResult player : players) {
                sql.execute("INSERT INTO `" + prefix + "players` (`id`, `uuid`) VALUES (?, ?);", player.getLongPlayerID(), player.getPlayerID().toString());
                longPlayerIDCache.put(player.getPlayerID(), player.getLongPlayerID());
            }
            if (truncate) {
                sql.execute("SET FOREIGN_KEY_CHECKS = 1;");
            }
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<RawChatResult> dumpChat(long begin, int size) throws StorageException {
        Set<RawChatResult> retVal = new LinkedHashSet<>();

        SQLQueryResult result;
        try {
            result = sql.query("SELECT `id`, `server_id`, `player_id`, `level`, `message`, `date` FROM `" + prefix + "posted_chat` LIMIT ?, ?;", begin - 1, size);
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }

        for (Object[] row : result.getData()) {
            retVal.add(new RawChatResult(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).byteValue(),
                    (String) row[4],
                    ((Timestamp) row[5]).getTime()
            ));
        }

        return retVal;
    }

    public void loadChat(Set<RawChatResult> chat, boolean truncate) throws StorageException {
        // TODO: Batch execute
        try {
            if (truncate) {
                sql.execute("SET FOREIGN_KEY_CHECKS = 0;");
                sql.execute("TRUNCATE `" + prefix + "posted_chat`;");
            }
            for (RawChatResult c : chat) {
                sql.execute("INSERT INTO `" + prefix + "posted_chat` (`id`, `server_id`, `player_id`, `level`, `message`, `date`) VALUES (?, ?, ?, ?, ?, ?);", c.getID(), c.getLongServerID(), c.getLongPlayerID(), c.getLevel(), c.getMessage(), new Timestamp(c.getDate()));
            }
            if (truncate) {
                sql.execute("SET FOREIGN_KEY_CHECKS = 1;");
            }
        } catch (SQLException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    private ChatResult getResult(Object[] row) {
        String serverID = (String) row[1];
        String playerID = (String) row[3];
        if (!ValidationUtil.isValidUuid(serverID)) {
            logger.warn("Chat ID " + row[0] + " has an invalid server ID \"" + row[1] + "\".");
            return null;
        }
        if (!ValidationUtil.isValidUuid(playerID)) {
            logger.warn("Chat ID " + row[0] + " has an invalid player ID \"" + row[3] + "\".");
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
                ((Timestamp) row[7]).getTime()
        );
    }

    private String getLevelExpensive(byte level) throws SQLException, StorageException {
        SQLQueryResult result = sql.query("SELECT `name` FROM `" + prefix + "levels` WHERE `id`=?;", level);
        if (result.getData().length != 1) {
            throw new StorageException(false, "Could not get level from ID " + level + ".");
        }

        return (String) result.getData()[0][0];
    }

    private long getLongPlayerIDExpensive(UUID uuid) throws SQLException, StorageException {
        // A majority of the time there'll be an ID
        SQLQueryResult result = sql.query("SELECT `id` FROM `" + prefix + "players` WHERE `uuid`=?;", uuid.toString());
        if (result.getData().length == 1) {
            return ((Number) result.getData()[0][0]).longValue();
        }

        // No ID, generate one
        SQLExecuteResult r = sql.execute("INSERT INTO `" + prefix + "players` (`uuid`) VALUES (?);", uuid.toString());
        if (r.getAutoGeneratedKeys().length != 1) {
            throw new StorageException(false, "Could not get generated keys from inserted player.");
        }
        long id = ((Number) r.getAutoGeneratedKeys()[0]).longValue();
        handler.playerIDCreationCallback(uuid, id, this);
        return id;
    }

    protected boolean isAutomaticallyRecoverable(SQLException ex) {
        if (
                ex.getErrorCode() == MysqlErrorNumbers.ER_LOCK_WAIT_TIMEOUT
                || ex.getErrorCode() == MysqlErrorNumbers.ER_QUERY_TIMEOUT
                || ex.getErrorCode() == MysqlErrorNumbers.ER_CON_COUNT_ERROR
                || ex.getErrorCode() == MysqlErrorNumbers.ER_TOO_MANY_DELAYED_THREADS
                || ex.getErrorCode() == MysqlErrorNumbers.ER_BINLOG_PURGE_EMFILE
                || ex.getErrorCode() == MysqlErrorNumbers.ER_TOO_MANY_CONCURRENT_TRXS
                || ex.getErrorCode() == MysqlErrorNumbers.ER_OUTOFMEMORY
                || ex.getErrorCode() == MysqlErrorNumbers.ER_OUT_OF_SORTMEMORY
                || ex.getErrorCode() == MysqlErrorNumbers.ER_CANT_CREATE_THREAD
                || ex.getErrorCode() == MysqlErrorNumbers.ER_OUT_OF_RESOURCES
                || ex.getErrorCode() == MysqlErrorNumbers.ER_ENGINE_OUT_OF_MEMORY
        ) {
            return true;
        }
        return false;
    }
}
