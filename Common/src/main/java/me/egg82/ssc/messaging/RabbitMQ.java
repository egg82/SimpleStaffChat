package me.egg82.ssc.messaging;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import me.egg82.ssc.services.MessagingHandler;
import me.egg82.ssc.utils.ValidationUtil;
import ninja.egg82.analytics.utils.JSONUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RabbitMQ implements Messaging {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    // TODO: connection recovery?
    // https://www.rabbitmq.com/api-guide.html#recovery
    private ConnectionFactory factory;
    // "Connection" acts as our pool
    // https://stackoverflow.com/questions/10407760/is-there-a-performance-difference-between-pooling-connections-or-channels-in-rab
    private RecoverableConnection connection;

    private String serverID;
    private UUID uuidServerID;
    private MessagingHandler handler;

    private RabbitMQ() { }

    private volatile boolean closed = false;

    public void close() {
        closed = true;
        try {
            connection.close(8000);
        } catch (IOException ignored) { }
    }

    public boolean isClosed() { return closed || !connection.isOpen(); }

    public static RabbitMQ.Builder builder(UUID serverID, MessagingHandler handler) { return new RabbitMQ.Builder(serverID, handler); }

    public static class Builder {
        private final RabbitMQ result = new RabbitMQ();
        private final ConnectionFactory config = new ConnectionFactory();

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

        public RabbitMQ.Builder url(String address, int port, String vHost) {
            config.setHost(address);
            config.setPort(port);
            config.setVirtualHost(vHost);
            return this;
        }

        public RabbitMQ.Builder credentials(String user, String pass) {
            config.setUsername(user);
            config.setPassword(pass);
            return this;
        }

        public RabbitMQ.Builder timeout(int timeout) {
            config.setConnectionTimeout(timeout);
            return this;
        }

        public RabbitMQ build() throws MessagingException {
            result.factory = config;
            try {
                result.connection = result.getConnection();
                // Bind queues
                result.bind();
            } catch (IOException | TimeoutException ex) {
                throw new MessagingException(false, "Could not create RabbitMQ connection.", ex);
            }
            return result;
        }
    }

    private void bind() throws IOException {
        RecoverableChannel levelChannel = getChannel();
        levelChannel.exchangeDeclare("simplestaffchat-level", ExchangeType.FANOUT.getType(), true);
        String levelQueue = levelChannel.queueDeclare().getQueue();
        levelChannel.queueBind(levelQueue, "simplestaffchat-level", "");
        Consumer levelConsumer = new DefaultConsumer(levelChannel) {
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) throws IOException {
                try {
                    receiveLevel(props, new String(body, props.getContentEncoding()));
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not parse incoming data.", ex);
                }
            }
        };
        levelChannel.basicConsume(levelQueue, true, levelConsumer);

        RecoverableChannel serverChannel = getChannel();
        serverChannel.exchangeDeclare("simplestaffchat-server", ExchangeType.FANOUT.getType(), true);
        String serverQueue = serverChannel.queueDeclare().getQueue();
        serverChannel.queueBind(serverQueue, "simplestaffchat-server", "");
        Consumer serverConsumer = new DefaultConsumer(serverChannel) {
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) throws IOException {
                try {
                    receiveServer(props, new String(body, props.getContentEncoding()));
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not parse incoming data.", ex);
                }
            }
        };
        serverChannel.basicConsume(serverQueue, true, serverConsumer);

        RecoverableChannel playerChannel = getChannel();
        playerChannel.exchangeDeclare("simplestaffchat-player", ExchangeType.FANOUT.getType(), true);
        String playerQueue = playerChannel.queueDeclare().getQueue();
        playerChannel.queueBind(playerQueue, "simplestaffchat-player", "");
        Consumer playerConsumer = new DefaultConsumer(playerChannel) {
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) throws IOException {
                try {
                    receivePlayer(props, new String(body, props.getContentEncoding()));
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not parse incoming data.", ex);
                }
            }
        };
        playerChannel.basicConsume(playerQueue, true, playerConsumer);

        RecoverableChannel postChannel = getChannel();
        postChannel.exchangeDeclare("simplestaffchat-post", ExchangeType.FANOUT.getType(), true);
        String postQueue = postChannel.queueDeclare().getQueue();
        postChannel.queueBind(postQueue, "simplestaffchat-post", "");
        Consumer postConsumer = new DefaultConsumer(postChannel) {
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) throws IOException {
                try {
                    receivePost(props, new String(body, props.getContentEncoding()));
                } catch (ParseException | ClassCastException ex) {
                    logger.warn("Could not parse incoming data.", ex);
                }
            }
        };
        postChannel.basicConsume(postQueue, true, postConsumer);
    }

    public void sendLevel(UUID messageID, byte level, String name) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null.");
        }

        try (RecoverableChannel channel = getChannel()) {
            JSONObject obj = new JSONObject();
            obj.put("level", level);
            obj.put("name", name);
            AMQP.BasicProperties props = getProperties(DeliveryMode.PERSISTENT);
            channel.exchangeDeclare("simplestaffchat-level", ExchangeType.FANOUT.getType(), true);
            channel.basicPublish("simplestaffchat-level", "", props, obj.toJSONString().getBytes(props.getContentEncoding()));
        } catch (IOException ex) {
            throw new MessagingException(false, ex);
        } catch (TimeoutException ex) {
            throw new MessagingException(true, ex);
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

        try (RecoverableChannel channel = getChannel()) {
            JSONObject obj = new JSONObject();
            obj.put("longID", longServerID);
            obj.put("id", serverID.toString());
            obj.put("name", name);
            AMQP.BasicProperties props = getProperties(DeliveryMode.PERSISTENT);
            channel.exchangeDeclare("simplestaffchat-server", ExchangeType.FANOUT.getType(), true);
            channel.basicPublish("simplestaffchat-server", "", props, obj.toJSONString().getBytes(props.getContentEncoding()));
        } catch (IOException ex) {
            throw new MessagingException(false, ex);
        } catch (TimeoutException ex) {
            throw new MessagingException(true, ex);
        }
    }

    public void sendPlayer(UUID messageID, long longPlayerID, UUID playerID) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (playerID == null) {
            throw new IllegalArgumentException("playerID cannot be null.");
        }

        try (RecoverableChannel channel = getChannel()) {
            JSONObject obj = new JSONObject();
            obj.put("longID", longPlayerID);
            obj.put("id", playerID.toString());
            AMQP.BasicProperties props = getProperties(DeliveryMode.PERSISTENT);
            channel.exchangeDeclare("simplestaffchat-player", ExchangeType.FANOUT.getType(), true);
            channel.basicPublish("simplestaffchat-player", "", props, obj.toJSONString().getBytes(props.getContentEncoding()));
        } catch (IOException ex) {
            throw new MessagingException(false, ex);
        } catch (TimeoutException ex) {
            throw new MessagingException(true, ex);
        }
    }

    public void sendPost(UUID messageID, long postID, long longServerID, UUID serverID, String serverName, long longPlayerID, UUID playerID, byte level, String levelName, String message, long date) throws MessagingException {
        if (messageID == null) {
            throw new IllegalArgumentException("messageID cannot be null.");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null.");
        }

        try (RecoverableChannel channel = getChannel()) {
            JSONObject obj = new JSONObject();
            obj.put("id", postID);
            obj.put("longServerID", longServerID);
            obj.put("serverID", serverID.toString());
            obj.put("serverName", serverName);
            obj.put("longPlayerID", longPlayerID);
            obj.put("playerID", playerID.toString());
            obj.put("level", level);
            obj.put("levelName", levelName);
            obj.put("message", message);
            obj.put("date", date);
            AMQP.BasicProperties props = getProperties(DeliveryMode.PERSISTENT);
            channel.exchangeDeclare("simplestaffchat-post", ExchangeType.FANOUT.getType(), true);
            channel.basicPublish("simplestaffchat-post", "", props, obj.toJSONString().getBytes(props.getContentEncoding()));
        } catch (IOException ex) {
            throw new MessagingException(false, ex);
        } catch (TimeoutException ex) {
            throw new MessagingException(true, ex);
        }
    }

    private AMQP.BasicProperties getProperties(DeliveryMode deliveryMode) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("sender", serverID);

        AMQP.BasicProperties.Builder retVal = new AMQP.BasicProperties.Builder();
        retVal.contentType("application/json");
        retVal.contentEncoding(StandardCharsets.UTF_8.name());
        retVal.messageId(UUID.randomUUID().toString());
        retVal.deliveryMode(deliveryMode.getMode());
        retVal.headers(headers);
        return retVal.build();
    }

    private void receiveLevel(AMQP.BasicProperties props, String json) throws ParseException, ClassCastException {
        if (props.getHeaders() == null || props.getHeaders().isEmpty()) {
            logger.warn("Properties for received level was null or empty.");
            return;
        }
        String sender = (String) props.getHeaders().get("sender");
        if (!ValidationUtil.isValidUuid(sender)) {
            logger.warn("Non-valid sender received in level: \"" + sender + "\".");
            return;
        }
        if (serverID.equals(sender)) {
            return;
        }

        if (!ValidationUtil.isValidUuid(props.getMessageId())) {
            logger.warn("Non-valid message ID received in level: \"" + props.getMessageId() + "\".");
            return;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        handler.levelCallback(
                UUID.fromString(props.getMessageId()),
                ((Number) obj.get("level")).byteValue(),
                (String) obj.get("name"),
                this
        );
    }

    private void receiveServer(AMQP.BasicProperties props, String json) throws ParseException, ClassCastException {
        if (props.getHeaders() == null || props.getHeaders().isEmpty()) {
            logger.warn("Properties for received server was null or empty.");
            return;
        }
        String sender = (String) props.getHeaders().get("sender");
        if (!ValidationUtil.isValidUuid(sender)) {
            logger.warn("Non-valid sender received in server: \"" + sender + "\".");
            return;
        }
        if (serverID.equals(sender)) {
            return;
        }

        if (!ValidationUtil.isValidUuid(props.getMessageId())) {
            logger.warn("Non-valid message ID received in server: \"" + props.getMessageId() + "\".");
            return;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        String id = (String) obj.get("id");
        if (!ValidationUtil.isValidUuid(id)) {
            logger.warn("Non-valid UUID received in server: \"" + id + "\".");
            return;
        }

        handler.serverCallback(
                UUID.fromString(props.getMessageId()),
                ((Number) obj.get("longID")).longValue(),
                UUID.fromString(id),
                (String) obj.get("name"),
                this
        );
    }

    private void receivePlayer(AMQP.BasicProperties props, String json) throws ParseException, ClassCastException {
        if (props.getHeaders() == null || props.getHeaders().isEmpty()) {
            logger.warn("Properties for received server was null or empty.");
            return;
        }
        String sender = (String) props.getHeaders().get("sender");
        if (!ValidationUtil.isValidUuid(sender)) {
            logger.warn("Non-valid sender received in player: \"" + sender + "\".");
            return;
        }
        if (serverID.equals(sender)) {
            return;
        }

        if (!ValidationUtil.isValidUuid(props.getMessageId())) {
            logger.warn("Non-valid message ID received in player: \"" + props.getMessageId() + "\".");
            return;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        String id = (String) obj.get("id");
        if (!ValidationUtil.isValidUuid(id)) {
            logger.warn("Non-valid UUID received in player: \"" + id + "\".");
            return;
        }

        handler.playerCallback(
                UUID.fromString(props.getMessageId()),
                UUID.fromString(id),
                ((Number) obj.get("longID")).longValue(),
                this
        );
    }

    private void receivePost(AMQP.BasicProperties props, String json) throws ParseException, ClassCastException {
        if (props.getHeaders() == null || props.getHeaders().isEmpty()) {
            logger.warn("Properties for received server was null or empty.");
            return;
        }
        String sender = (String) props.getHeaders().get("sender");
        if (!ValidationUtil.isValidUuid(sender)) {
            logger.warn("Non-valid sender received in player: \"" + sender + "\".");
            return;
        }
        if (serverID.equals(sender)) {
            return;
        }

        if (!ValidationUtil.isValidUuid(props.getMessageId())) {
            logger.warn("Non-valid message ID received in player: \"" + props.getMessageId() + "\".");
            return;
        }

        JSONObject obj = JSONUtil.parseObject(json);
        String serverID = (String) obj.get("serverID");
        if (!ValidationUtil.isValidUuid(serverID)) {
            logger.warn("Non-valid server ID received in post: \"" + serverID + "\".");
            return;
        }

        String playerID = (String) obj.get("playerID");
        if (!ValidationUtil.isValidUuid(playerID)) {
            logger.warn("Non-valid player ID received in post: \"" + serverID + "\".");
            return;
        }

        handler.postCallback(
                UUID.fromString(props.getMessageId()),
                ((Number) obj.get("id")).longValue(),
                ((Number) obj.get("longServerID")).longValue(),
                UUID.fromString(serverID),
                (String) obj.get("serverName"),
                ((Number) obj.get("longPlayerID")).longValue(),
                UUID.fromString(playerID),
                ((Number) obj.get("level")).byteValue(),
                (String) obj.get("levelName"),
                (String) obj.get("message"),
                ((Number) obj.get("date")).longValue(),
                this
        );
    }

    private RecoverableConnection getConnection() throws IOException, TimeoutException { return (RecoverableConnection) factory.newConnection(); }

    private RecoverableChannel getChannel() throws IOException { return (RecoverableChannel) connection.createChannel(); }

    private enum DeliveryMode {
        /**
         * Not logged to disk
         */
        TRANSIENT(1),
        /**
         * When in a durable exchange, logged to disk
         */
        PERSISTENT(2);

        private final int mode;
        DeliveryMode(int mode) { this.mode = mode; }
        public int getMode() { return mode; }
    }

    private enum ExchangeType {
        DIRECT("direct"),
        FANOUT("fanout"),
        TOPIC("topic"),
        HEADERS("match"); // AMQP compatibility

        private final String type;
        ExchangeType(String type) { this.type = type; }
        public String getType() { return type; }
    }
}
