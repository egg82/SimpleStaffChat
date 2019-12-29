package me.egg82.ssc.storage;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.egg82.ssc.core.ChatResult;
import me.egg82.ssc.core.PostChatResult;
import me.egg82.ssc.services.StorageHandler;
import ninja.egg82.analytics.utils.JSONUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

public class Redis implements Storage {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final LoadingCache<UUID, Long> longPlayerIDCache = Caffeine.newBuilder().build(this::getLongPlayerIDExpensive);

    private JedisPool pool;

    private String serverName;
    private String serverID;
    private UUID uuidServerID;
    private long longServerID = -1;
    private volatile long lastMessageID;
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
            try (Jedis redis = result.pool.getResource()) {
                redis.setnx(result.prefix + "levels:idx", "1");
                redis.setnx(result.prefix + "levels:1", "{\"name\":\"ALL\"}");
                redis.setnx(result.prefix + "servers:idx", "0");
                redis.setnx(result.prefix + "players:idx", "0");
                redis.setnx(result.prefix + "posts:idx", "0");
            }
            result.setServerName(result.serverName);
            result.longServerID = getLongServerID();
            result.lastMessageID = getLastMessageID();
            return result;
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
                long id = Long.parseLong(redis.get(result.prefix + "posts:idx"));
                while (redis.exists(result.prefix + "posts:" + (id + 1))) {
                    id = redis.incr(result.prefix + "posts:idx");
                }
                return id;
            } catch (JedisException ex) {
                throw new StorageException(false, "Could not get last message ID.");
            }
        }
    }

    public Set<ChatResult> getQueue() throws StorageException {

    }

    public Set<ChatResult> getByPlayer(UUID playerID, int days) throws StorageException {

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
                id = Long.parseLong(redis.get(prefix + "posts:idx"));
                while (redis.exists(prefix + "posts:" + id)) {
                    id = redis.incr(prefix + "posts:idx");
                }
                date = getTime(redis.time());
                obj.put("date", date);
            } while (redis.setnx(prefix + "posts:" + id, obj.toJSONString()) == 0L);

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

            redis.set(prefix + "posts:" + postID, obj.toJSONString());
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

    // Redis returns a list
    // o[0] = unix time in seconds
    // o[1] = microseconds since last second
    // Therefore, to get unix time in millis we multiply seconds by 1000, divide microseconds by 1000, and add them together
    private long getTime(List<String> o) { return Long.parseLong(o.get(0)) * 1000L + Long.parseLong(o.get(1)) / 1000L; }
}
