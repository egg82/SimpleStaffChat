package me.egg82.ssc.messaging;

import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import me.egg82.ssc.services.MessagingHandler;
import me.egg82.ssc.utils.ValidationUtil;
import ninja.egg82.analytics.utils.JSONUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

public class Redis extends JedisPubSub implements Messaging {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private JedisPool pool;

    private String serverID;
    private UUID uuidServerID;
    private MessagingHandler handler;

    private Redis() { }

    private volatile boolean closed = false;

    public void close() {
        closed = true;
        pool.close();
    }

    public boolean isClosed() { return closed || pool.isClosed(); }

    public static Redis.Builder builder(UUID serverID, MessagingHandler handler) { return new Redis.Builder(serverID, handler); }

    public static class Builder {
        private final Logger logger = LoggerFactory.getLogger(getClass());

        private final Redis result = new Redis();
        private final JedisPoolConfig config = new JedisPoolConfig();

        private String address = "127.0.0.1";
        private int port = 6379;
        private int timeout = 5000;
        private String pass = "";

        private Builder(UUID serverID, MessagingHandler handler) {
            if (serverID == null) {
                throw new IllegalArgumentException("serverID cannot be null.");
            }
            if (handler == null) {
                throw new IllegalArgumentException("handler cannot be null.");
            }

            result.uuidServerID = serverID;
            result.serverID = serverID.toString();
            result.handler = handler;
        }

        public Redis.Builder url(String address, int port) {
            this.address = address;
            this.port = port;
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

        public Redis build() throws MessagingException {
            result.pool = new JedisPool(config, address, port, timeout, pass);
            // Warm up pool
            // https://partners-intl.aliyun.com/help/doc-detail/98726.htm
            warmup(result.pool);
            // Indefinite subscription
            ForkJoinPool.commonPool().execute(() -> {
                while (!result.isClosed()) {
                    try (Jedis redis = result.pool.getResource()) {
                        redis.subscribe(result,
                                "simplestaffchat-level",
                                "simplestaffchat-server",
                                "simplestaffchat-player",
                                "simplestaffchat-post");
                    } catch (JedisException ex) {
                        if (!result.isClosed()) {
                            logger.warn("Redis pub/sub disconnected. Reconnecting..");
                        }
                    }
                }
            });
            return result;
        }

        private void warmup(JedisPool pool) throws MessagingException {
            Jedis[] warmpupArr = new Jedis[config.getMinIdle()];

            for (int i = 0; i < config.getMinIdle(); i++) {
                Jedis jedis;
                try {
                    jedis = pool.getResource();
                    warmpupArr[i] = jedis;
                    jedis.ping();
                } catch (JedisException ex) {
                    throw new MessagingException(false, "Could not warm up Redis connection.", ex);
                }
            }
            // Two loops because we need to ensure we don't pull a freshly-created resource from the pool
            for (int i = 0; i < config.getMinIdle(); i++) {
                Jedis jedis;
                try {
                    jedis = warmpupArr[i];
                    jedis.close();
                } catch (JedisException ex) {
                    throw new MessagingException(false, "Could not close warmed Redis connection.", ex);
                }
            }
        }
    }

    public void sendLevel(UUID messageID, byte level, String name) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null.");
        }

        try (Jedis redis = pool.getResource()) {
            JSONObject obj = createJSON(messageID);
            obj.put("level", level);
            obj.put("name", name);
            redis.publish("simplestaffchat-level", obj.toJSONString());
        } catch (JedisException ex) {
            throw new MessagingException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void sendServer(UUID messageID, long longServerID, UUID serverID, String name) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (serverID == null) {
            throw new IllegalArgumentException("serverID cannot be null.");
        }
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null.");
        }

        try (Jedis redis = pool.getResource()) {
            JSONObject obj = createJSON(messageID);
            obj.put("longID", longServerID);
            obj.put("id", serverID.toString());
            obj.put("name", name);
            redis.publish("simplestaffchat-server", obj.toJSONString());
        } catch (JedisException ex) {
            throw new MessagingException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void sendPlayer(UUID messageID, long longPlayerID, UUID playerID) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (playerID == null) {
            throw new IllegalArgumentException("playerID cannot be null.");
        }

        try (Jedis redis = pool.getResource()) {
            JSONObject obj = createJSON(messageID);
            obj.put("longID", longPlayerID);
            obj.put("id", playerID.toString());
            redis.publish("simplestaffchat-player", obj.toJSONString());
        } catch (JedisException ex) {
            throw new MessagingException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    public void sendPost(UUID messageID, long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null.");
        }

        try (Jedis redis = pool.getResource()) {
            JSONObject obj = createJSON(messageID);
            obj.put("id", postID);
            obj.put("serverID", longServerID);
            obj.put("playerID", longPlayerID);
            obj.put("level", level);
            obj.put("message", message);
            obj.put("date", date);
            redis.publish("simplestaffchat-post", obj.toJSONString());
        } catch (JedisException ex) {
            throw new MessagingException(isAutomaticallyRecoverable(ex), ex);
        }
    }

    private JSONObject createJSON(UUID messageID) {
        JSONObject retVal = new JSONObject();
        retVal.put("sender", serverID);
        retVal.put("messageID", messageID.toString());
        return retVal;
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

    public void onMessage(String channel, String message) {
        try {
            switch (channel) {
                case "simplestaffchat-level":
                    receiveLevel(message);
                    break;
                case "simplestaffchat-server":
                    receiveServer(message);
                    break;
                case "simplestaffchat-player":
                    receivePlayer(message);
                    break;
                case "simplestaffchat-post":
                    receivePost(message);
                    break;
                default:
                    logger.warn("Got data from channel that should not exist.");
                    break;
            }
        } catch (ParseException | ClassCastException ex) {
            logger.warn("Could not parse incoming data.", ex);
        }
    }

    private void receiveLevel(String json) throws ParseException, ClassCastException {
        JSONObject obj = JSONUtil.parseObject(json);
        String sender = (String) obj.get("sender");
        if (sender.equals(serverID)) {
            return;
        }

        String messageID = (String) obj.get("messageID");
        if (!ValidationUtil.isValidUuid(messageID)) {
            logger.warn("Non-valid message ID received in server: \"" + messageID + "\".");
            return;
        }

        handler.levelCallback(
                UUID.fromString(messageID),
                ((Number) obj.get("level")).byteValue(),
                (String) obj.get("name"),
                this
        );
    }

    private void receiveServer(String json) throws ParseException, ClassCastException {
        JSONObject obj = JSONUtil.parseObject(json);
        String sender = (String) obj.get("sender");
        if (sender.equals(serverID)) {
            return;
        }

        String messageID = (String) obj.get("messageID");
        if (!ValidationUtil.isValidUuid(messageID)) {
            logger.warn("Non-valid message ID received in server: \"" + messageID + "\".");
            return;
        }

        String id = (String) obj.get("id");
        if (!ValidationUtil.isValidUuid(id)) {
            logger.warn("Non-valid UUID received in server: \"" + id + "\".");
            return;
        }

        handler.serverCallback(
                UUID.fromString(messageID),
                ((Number) obj.get("longID")).longValue(),
                UUID.fromString(id),
                (String) obj.get("name"),
                this
        );
    }

    private void receivePlayer(String json) throws ParseException, ClassCastException {
        JSONObject obj = JSONUtil.parseObject(json);
        String sender = (String) obj.get("sender");
        if (sender.equals(serverID)) {
            return;
        }

        String messageID = (String) obj.get("messageID");
        if (!ValidationUtil.isValidUuid(messageID)) {
            logger.warn("Non-valid message ID received in server: \"" + messageID + "\".");
            return;
        }

        String id = (String) obj.get("id");
        if (!ValidationUtil.isValidUuid(id)) {
            logger.warn("Non-valid UUID received in player: \"" + id + "\".");
            return;
        }

        handler.playerCallback(
                UUID.fromString(messageID),
                UUID.fromString(id),
                ((Number) obj.get("longID")).longValue(),
                this
        );
    }

    private void receivePost(String json) throws ParseException, ClassCastException {
        JSONObject obj = JSONUtil.parseObject(json);
        String sender = (String) obj.get("sender");
        if (sender.equals(serverID)) {
            return;
        }

        String messageID = (String) obj.get("messageID");
        if (!ValidationUtil.isValidUuid(messageID)) {
            logger.warn("Non-valid message ID received in server: \"" + messageID + "\".");
            return;
        }

        handler.postCallback(
                UUID.fromString(messageID),
                ((Number) obj.get("id")).longValue(),
                ((Number) obj.get("serverID")).longValue(),
                ((Number) obj.get("playerID")).longValue(),
                ((Number) obj.get("level")).byteValue(),
                (String) obj.get("message"),
                ((Number) obj.get("date")).longValue(),
                this
        );
    }
}
