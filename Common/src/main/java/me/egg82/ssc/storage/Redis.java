package me.egg82.ssc.storage;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import me.egg82.ssc.core.*;
import me.egg82.ssc.services.StorageHandler;
import me.egg82.ssc.utils.ValidationUtil;
import ninja.egg82.analytics.utils.JSONUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisException;

public class Redis implements Storage {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final LoadingCache<UUID, Long> longPlayerIDCache = Caffeine.newBuilder().build(this::getLongPlayerIDExpensive);

    private JedisPool pool;

    private String serverName;
    private String serverID;
    private UUID uuidServerID;
    private long longServerID = -1;
    private AtomicLong lastMessageID;
    private StorageHandler handler;
    protected String prefix = "";

    private Redis() {
    }

    private volatile boolean closed = false;

    public void close() {
        closed = true;
        pool.close();
    }

    public boolean isClosed() {
        return closed || pool.isClosed();
    }

    public static Redis.Builder builder(UUID serverID, String serverName, StorageHandler handler) {
        return new Redis.Builder(serverID, serverName, handler);
    }

    public static class Builder {
        private final Logger logger = LoggerFactory.getLogger(getClass());

        private final Redis result = new Redis();
        private final JedisPoolConfig config = new JedisPoolConfig();

        private String address = "127.0.0.1";
        private int port = 6379;
        private int timeout = 5000;
        private String pass = "";

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
        }

        public Redis.Builder url(String address, int port, String prefix) {
            this.address = address;
            this.port = port;
            result.prefix = prefix;
            return this;
        }

        public Redis.Builder credentials(String pass) {
            this.pass = pass;
            return this;
        }

        public Redis.Builder poolSize(int min, int max) {
            config.setMinIdle(min);
            config.setMaxTotal(max);
            return this;
        }

        public Redis.Builder life(long lifetime, int timeout) {
            config.setMinEvictableIdleTimeMillis(lifetime);
            config.setMaxWaitMillis(timeout);
            this.timeout = timeout;
            return this;
        }

        public Redis build() throws StorageException {
            result.pool = new JedisPool(config, address, port, timeout, pass);
            // Warm up pool
            // https://partners-intl.aliyun.com/help/doc-detail/98726.htm
            warmup(result.pool);
            setDefaults();
            result.setServerName(result.serverName);
            result.longServerID = getLongServerID();
            result.lastMessageID = new AtomicLong(getLastMessageID());
            return result;
        }

        private void setDefaults() {
            try (Jedis redis = result.pool.getResource()) {
                redis.setnx(result.prefix + "levels:idx", "1");
                redis.setnx(result.prefix + "levels:1", "{\"name\":\"ALL\"}");
                redis.setnx(result.prefix + "servers:idx", "0");
                redis.setnx(result.prefix + "players:idx", "0");
                redis.setnx(result.prefix + "posted_chat:idx", "0");
            }
        }

        private void warmup(JedisPool pool) throws StorageException {
            Jedis[] warmpupArr = new Jedis[config.getMinIdle()];

            for (int i = 0; i < config.getMinIdle(); i++) {
                Jedis jedis;
                try {
                    jedis = pool.getResource();
                    warmpupArr[i] = jedis;
                    jedis.ping();
                } catch (JedisException ex) {
                    throw new StorageException(false, "Could not warm up Redis connection.", ex);
                }
            }
            // Two loops because we need to ensure we don't pull a freshly-created resource from the pool
            for (int i = 0; i < config.getMinIdle(); i++) {
                Jedis jedis;
                try {
                    jedis = warmpupArr[i];
                    jedis.close();
                } catch (JedisException ex) {
                    throw new StorageException(false, "Could not close warmed Redis connection.", ex);
                }
            }
        }

        private long getLongServerID() throws StorageException {
            try (Jedis redis = result.pool.getResource()) {
                String json = redis.get(result.prefix + "servers:" + result.serverID);
                if (json != null) {
                    JSONObject obj = null;
                    try {
                        obj = JSONUtil.parseObject(json);
                    } catch (ParseException | ClassCastException ex) {
                        redis.del(result.prefix + "servers:" + result.serverID);
                        logger.warn("Could not parse server data. Deleted key.");
                    }
                    if (obj != null) {
                        return ((Number) obj.get("longID")).longValue();
                    }
                }

                long id = Long.parseLong(redis.get(result.prefix + "servers:idx"));
                while (redis.exists(result.prefix + "servers:" + id)) {
                    id = redis.incr(result.prefix + "servers:idx");
                }

                JSONObject obj = new JSONObject();
                obj.put("id", result.serverID);
                obj.put("name", result.serverName);

                JSONObject obj2 = new JSONObject();
                obj2.put("longID", id);
                obj2.put("name", result.serverName);

                redis.mset(
                        result.prefix + "servers:" + id, obj.toJSONString(),
                        result.prefix + "servers:" + result.serverID, obj2.toJSONString()
                );
                return id;
            } catch (JedisException ex) {
                throw new StorageException(false, "Could not get server ID.");
            }
        }

        private long getLastMessageID() throws StorageException {
            try (Jedis redis = result.pool.getResource()) {
                long id = Long.parseLong(redis.get(result.prefix + "posted_chat:idx"));
                while (redis.exists(result.prefix + "posted_chat:" + (id + 1))) {
                    id = redis.incr(result.prefix + "posted_chat:idx");
                }
                return id;
            } catch (JedisException ex) {
                throw new StorageException(false, "Could not get last message ID.");
            }
        }
    }

    public Set<ChatResult> getQueue() throws StorageException {
        Set<ChatResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            long max = Long.parseLong(redis.get(prefix + "posted_chat:idx"));
            while (redis.exists(prefix + "posted_chat:" + (max + 1))) {
                max = redis.incr(prefix + "posted_chat:idx");
            }

            if (lastMessageID.get() >= max) {
                lastMessageID.set(max);
                return retVal;
            }

            long next;
            while ((next = lastMessageID.getAndIncrement()) < max) {
                ChatResult r = null;
                try {
                    r = getResult(next, redis.get(prefix + "posted_chat:" + next), redis);
                } catch (StorageException | JedisException | ParseException | ClassCastException ex) {
                    logger.warn("Could not get post data for ID " + next + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<ChatResult> getByPlayer(UUID playerID, int days) throws StorageException {
        Set<ChatResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            long longPlayerID = longPlayerIDCache.get(playerID);
            long len = redis.llen(prefix + "posted_chat:player:" + longPlayerID);

            for (long i = 0L; i < len; i++) {
                ChatResult r = null;
                try {
                    r = getResultPlayer(longPlayerID, redis.lindex(prefix + "posted_chat:player:" + longPlayerID, i), redis);
                } catch (StorageException | JedisException | ParseException | ClassCastException ex) {
                    logger.warn("Could not get post data for player " + longPlayerID + " at index " + i + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public PostChatResult post(UUID playerID, byte level, String message) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            long longPlayerID = longPlayerIDCache.get(playerID);

            JSONObject obj = new JSONObject();
            obj.put("serverID", longServerID);
            obj.put("playerID", longPlayerID);
            obj.put("level", level);
            obj.put("message", message);

            long id;
            long date;
            do {
                id = Long.parseLong(redis.get(prefix + "posted_chat:idx"));
                while (redis.exists(prefix + "posted_chat:" + id)) {
                    id = redis.incr(prefix + "posted_chat:idx");
                }
                date = getTime(redis.time());
                obj.put("date", date);
            } while (redis.setnx(prefix + "posted_chat:" + id, obj.toJSONString()) == 0L);

            obj.remove("playerID");
            obj.put("id", id);
            redis.rpush(prefix + "posted_chat:player:" + longPlayerID, obj.toJSONString());

            return new PostChatResult(
                    id,
                    longServerID,
                    longPlayerID,
                    level,
                    message,
                    date
            );
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setLevelRaw(byte level, String name) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            redis.set(prefix + "levels:" + level, obj.toJSONString());
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setServerRaw(long longServerID, UUID serverID, String name) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            JSONObject obj = new JSONObject();
            obj.put("id", serverID.toString());
            obj.put("name", name);

            JSONObject obj2 = new JSONObject();
            obj2.put("longID", longServerID);
            obj2.put("name", name);

            redis.mset(
                    prefix + "servers:" + longServerID, obj.toJSONString(),
                    prefix + "servers:" + serverID.toString(), obj2.toJSONString()
            );
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void setPlayerRaw(long longPlayerID, UUID playerID) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            JSONObject obj = new JSONObject();
            obj.put("id", playerID.toString());

            JSONObject obj2 = new JSONObject();
            obj2.put("longID", longPlayerID);

            redis.mset(
                    prefix + "players:" + longPlayerID, obj.toJSONString(),
                    prefix + "players:" + playerID.toString(), obj2.toJSONString()
            );
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void postRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            JSONObject obj = new JSONObject();
            obj.put("serverID", longServerID);
            obj.put("playerID", longPlayerID);
            obj.put("level", level);
            obj.put("message", message);
            obj.put("date", date);

            redis.set(prefix + "posted_chat:" + postID, obj.toJSONString());

            obj.remove("playerID");
            obj.put("id", postID);
            redis.rpush(prefix + "posted_chat:player:" + longPlayerID, obj.toJSONString());
        } catch (JedisException ex) {
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
        try (Jedis redis = pool.getResource()) {
            JSONObject obj = new JSONObject();
            obj.put("id", serverID);
            obj.put("name", name);

            JSONObject obj2 = new JSONObject();
            obj2.put("longID", longServerID);
            obj2.put("name", name);
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public long getLongPlayerID(UUID playerID) { return longPlayerIDCache.get(playerID); }

    public Set<LevelResult> dumpLevels() throws StorageException {
        Set<LevelResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            byte max = Byte.parseByte(redis.get(prefix + "levels:idx"));
            while (redis.exists(prefix + "levels:" + (max + 1))) {
                max = redis.incr(prefix + "levels:idx").byteValue();
            }

            for (byte i = 0; i < max; i++) {
                LevelResult r = null;
                try {
                    String json = redis.get(prefix + "levels:" + i);
                    JSONObject obj = JSONUtil.parseObject(json);
                    r = new LevelResult(
                            i,
                            (String) obj.get("name")
                    );
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not get level data for ID " + i + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void loadLevels(Set<LevelResult> levels) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            deleteNamespace(redis, prefix + "levels:");
            byte max = 0;
            for (LevelResult level : levels) {
                max = (byte) Math.max(max, level.getLevel());
                JSONObject obj = new JSONObject();
                obj.put("name", level.getName());
                redis.set(prefix + "levels:" + level.getLevel(), obj.toJSONString());
            }
            redis.set(prefix + "levels:idx", String.valueOf(max));
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<ServerResult> dumpServers() throws StorageException {
        Set<ServerResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            long max = Byte.parseByte(redis.get(prefix + "servers:idx"));
            while (redis.exists(prefix + "servers:" + (max + 1))) {
                max = redis.incr(prefix + "servers:idx");
            }

            for (long i = 0; i < max; i++) {
                ServerResult r = null;
                try {
                    String json = redis.get(prefix + "servers:" + i);
                    JSONObject obj = JSONUtil.parseObject(json);
                    String sid = (String) obj.get("id");
                    if (!ValidationUtil.isValidUuid(sid)) {
                        logger.warn("Server ID " + i + " has an invalid UUID \"" + sid + "\".");
                        continue;
                    }

                    r = new ServerResult(
                            i,
                            UUID.fromString(sid),
                            (String) obj.get("name")
                    );
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not get server data for ID " + i + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void loadServers(Set<ServerResult> servers) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            deleteNamespace(redis, prefix + "servers:");
            long max = 0;
            for (ServerResult server : servers) {
                max = Math.max(max, server.getLongServerID());
                JSONObject obj = new JSONObject();
                obj.put("id", server.getServerID().toString());
                obj.put("name", server.getName());

                JSONObject obj2 = new JSONObject();
                obj2.put("longID", server.getLongServerID());
                obj2.put("name", server.getName());

                redis.mset(
                        prefix + "servers:" + server.getLongServerID(), obj.toJSONString(),
                        prefix + "servers:" + server.getServerID().toString(), obj2.toJSONString()
                );
            }
            redis.set(prefix + "servers:idx", String.valueOf(max));
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<PlayerResult> dumpPlayers(long begin, int size) throws StorageException {
        Set<PlayerResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            for (long i = begin; i < begin + size; i++) {
                PlayerResult r = null;
                try {
                    String json = redis.get(prefix + "players:" + i);
                    JSONObject obj = JSONUtil.parseObject(json);
                    String pid = (String) obj.get("id");
                    if (!ValidationUtil.isValidUuid(pid)) {
                        logger.warn("Player ID " + i + " has an invalid UUID \"" + pid + "\".");
                        continue;
                    }

                    r = new PlayerResult(
                            i,
                            UUID.fromString(pid)
                    );
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not get player data for ID " + i + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void loadPlayers(Set<PlayerResult> players, boolean truncate) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            if (truncate) {
                deleteNamespace(redis, prefix + "players:");
            }
            long max = 0;
            for (PlayerResult player : players) {
                max = Math.max(max, player.getLongPlayerID());

                JSONObject obj = new JSONObject();
                obj.put("id", player.getPlayerID().toString());

                JSONObject obj2 = new JSONObject();
                obj2.put("longID", player.getLongPlayerID());

                redis.mset(
                        prefix + "players:" + player.getLongPlayerID(), obj.toJSONString(),
                        prefix + "players:" + player.getPlayerID().toString(), obj2.toJSONString()
                );
            }
            redis.set(prefix + "players:idx", String.valueOf(max));
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public Set<PostChatResult> dumpChat(long begin, int size) throws StorageException {
        Set<PostChatResult> retVal = new LinkedHashSet<>();

        try (Jedis redis = pool.getResource()) {
            for (long i = begin; i < begin + size; i++) {
                PostChatResult r = null;
                try {
                    String json = redis.get(prefix + "posted_chat:" + i);
                    JSONObject obj = JSONUtil.parseObject(json);
                    r = new PostChatResult(
                            i,
                            ((Number) obj.get("serverID")).longValue(),
                            ((Number) obj.get("playerID")).longValue(),
                            ((Number) obj.get("level")).byteValue(),
                            (String) obj.get("message"),
                            ((Number) obj.get("date")).longValue()
                    );
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not get chat data for ID " + i + ".", ex);
                }
                if (r != null) {
                    retVal.add(r);
                }
            }

            return retVal;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void loadChat(Set<PostChatResult> chat, boolean truncate) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            if (truncate) {
                deleteNamespace(redis, prefix + "posted_chat:");
            }
            long max = 0;
            for (PostChatResult c : chat) {
                max = Math.max(max, c.getID());
                JSONObject obj = new JSONObject();
                obj.put("serverID", c.getLongServerID());
                obj.put("playerID", c.getLongPlayerID());
                obj.put("level", c.getLevel());
                obj.put("message", c.getMessage());
                obj.put("date", c.getDate());

                redis.set(prefix + "posted_chat:" + c.getID(), obj.toJSONString());

                obj.remove("playerID");
                obj.put("id", c.getID());
                redis.rpush(prefix + "posted_chat:player:" + c.getLongPlayerID(), obj.toJSONString());
            }
            redis.set(prefix + "posted_chat:idx", String.valueOf(max));
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    private void deleteNamespace(Jedis redis, String namespace) throws JedisException {
        long current = 0;
        ScanParams params = new ScanParams();
        params.match(namespace + "*");
        params.count(50);

        ScanResult<String> result;
        do {
            result = redis.scan(String.valueOf(current), params);
            redis.del(result.getResult().toArray(new String[0]));
            current = Long.parseLong(result.getCursor());
        } while (!result.isCompleteIteration());
    }

    private long getLongPlayerIDExpensive(UUID uuid) throws StorageException {
        try (Jedis redis = pool.getResource()) {
            // A majority of the time there'll be an ID
            String json = redis.get(prefix + "players:" + uuid.toString());
            if (json != null) {
                JSONObject obj = null;
                try {
                    obj = JSONUtil.parseObject(json);
                } catch (ParseException | ClassCastException ex) {
                    redis.del(prefix + "players:" + uuid.toString());
                    logger.warn("Could not parse player data. Deleted key.");
                }
                if (obj != null) {
                    return ((Number) obj.get("longID")).longValue();
                }
            }

            // No ID, generate one
            JSONObject obj = new JSONObject();
            obj.put("id", uuid.toString());

            JSONObject obj2 = new JSONObject();

            long id;
            do {
                do {
                    id = redis.incr(prefix + "players:idx");
                } while (redis.exists(prefix + "players:" + id));
                obj2.put("longID", id);
            } while (redis.msetnx(
                    prefix + "players:" + id, obj.toJSONString(),
                    prefix + "players:" + uuid.toString(), obj2.toJSONString()
            ) == 0L);

            handler.playerIDCreationCallback(uuid, id, this);
            return id;
        } catch (JedisException ex) {
            throw new StorageException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    private boolean isAutomaticallyRecoverable(JedisException ex) {
        if (
                ex.getMessage().startsWith("Failed connecting")
                || ex.getMessage().contains("broken connection")
        ) {
            return true;
        }
        return false;
    }

    private ChatResult getResult(long id, String json, Jedis redis) throws StorageException, JedisException, ParseException, ClassCastException {
        if (json == null) {
            return null;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        long longServerID = ((Number) obj.get("serverID")).longValue();
        long longPlayerID = ((Number) obj.get("playerID")).longValue();
        byte level = ((Number) obj.get("level")).byteValue();
        String message = (String) obj.get("message");
        long date = ((Number) obj.get("date")).longValue();

        String serverJSON = redis.get(prefix + "servers:" + longServerID);
        if (serverJSON == null) {
            throw new StorageException(false, "Could not get server data for ID " + longServerID + ".");
        }
        JSONObject serverObj = JSONUtil.parseObject(serverJSON);
        String sid = (String) serverObj.get("id");
        if (!ValidationUtil.isValidUuid(sid)) {
            redis.del(prefix + "servers:" + longServerID);
            throw new StorageException(false, "Server ID " + longServerID + " has an invalid UUID \"" + sid + "\".");
        }

        String playerJSON = redis.get(prefix + "players:" + longPlayerID);
        if (playerJSON == null) {
            throw new StorageException(false, "Could not get player data for ID " + longPlayerID + ".");
        }
        JSONObject playerObj = JSONUtil.parseObject(playerJSON);
        String pid = (String) playerObj.get("id");
        if (!ValidationUtil.isValidUuid(pid)) {
            redis.del(prefix + "players:" + longPlayerID);
            throw new StorageException(false, "Player ID " + longServerID + " has an invalid UUID \"" + pid + "\".");
        }

        String levelJSON = redis.get(prefix + "levels:" + level);
        if (levelJSON == null) {
            throw new StorageException(false, "Could not get level data for ID " + level + ".");
        }
        JSONObject levelObj = JSONUtil.parseObject(levelJSON);
        String levelName = (String) levelObj.get("name");

        return new ChatResult(
                id,
                UUID.fromString(sid),
                serverName,
                UUID.fromString(pid),
                level,
                levelName,
                message,
                date
        );
    }

    private ChatResult getResultPlayer(long longPlayerID, String json, Jedis redis) throws StorageException, JedisException, ParseException, ClassCastException {
        if (json == null) {
            return null;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        long id = ((Number) obj.get("id")).longValue();
        long longServerID = ((Number) obj.get("serverID")).longValue();
        byte level = ((Number) obj.get("level")).byteValue();
        String message = (String) obj.get("message");
        long date = ((Number) obj.get("date")).longValue();

        String serverJSON = redis.get(prefix + "servers:" + longServerID);
        if (serverJSON == null) {
            throw new StorageException(false, "Could not get server data for ID " + longServerID + ".");
        }
        JSONObject serverObj = JSONUtil.parseObject(serverJSON);
        String sid = (String) serverObj.get("id");
        if (!ValidationUtil.isValidUuid(sid)) {
            redis.del(prefix + "servers:" + longServerID);
            throw new StorageException(false, "Server ID " + longServerID + " has an invalid UUID \"" + sid + "\".");
        }

        String playerJSON = redis.get(prefix + "players:" + longPlayerID);
        if (playerJSON == null) {
            throw new StorageException(false, "Could not get player data for ID " + longPlayerID + ".");
        }
        JSONObject playerObj = JSONUtil.parseObject(playerJSON);
        String pid = (String) playerObj.get("id");
        if (!ValidationUtil.isValidUuid(pid)) {
            redis.del(prefix + "players:" + longPlayerID);
            throw new StorageException(false, "Player ID " + longServerID + " has an invalid UUID \"" + pid + "\".");
        }

        String levelJSON = redis.get(prefix + "levels:" + level);
        if (levelJSON == null) {
            throw new StorageException(false, "Could not get level data for ID " + level + ".");
        }
        JSONObject levelObj = JSONUtil.parseObject(levelJSON);
        String levelName = (String) levelObj.get("name");

        return new ChatResult(
                id,
                UUID.fromString(sid),
                serverName,
                UUID.fromString(pid),
                level,
                levelName,
                message,
                date
        );
    }

    // Redis returns a list
    // o[0] = unix time in seconds
    // o[1] = microseconds since last second
    // Therefore, to get unix time in millis we multiply seconds by 1000, divide microseconds by 1000, and add them together
    private long getTime(List<String> o) { return Long.parseLong(o.get(0)) * 1000L + Long.parseLong(o.get(1)) / 1000L; }
}
