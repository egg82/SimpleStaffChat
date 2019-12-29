package me.egg82.ssc.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.egg82.ssc.core.ChatResult;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.extended.PostHandler;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageMessagingHandler implements StorageHandler, MessagingHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private LoadingCache<UUID, Boolean> cachedMessages = Caffeine.newBuilder().expireAfterAccess(5L, TimeUnit.MINUTES).expireAfterWrite(10L, TimeUnit.MINUTES).build(k -> Boolean.FALSE);
    private LoadingCache<Long, Boolean> cachedPosts = Caffeine.newBuilder().expireAfterAccess(2L, TimeUnit.MINUTES).expireAfterWrite(5L, TimeUnit.MINUTES).build(k -> Boolean.FALSE);

    private ExecutorService workPool = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("SimpleStaffChat-SMH-%d").build());

    private final PostHandler handler;

    public StorageMessagingHandler(PostHandler handler) {
        this.handler = handler;
        workPool.execute(this::getQueue);
    }

    public void close() {
        workPool.shutdown();
        try {
            if (!workPool.awaitTermination(4L, TimeUnit.SECONDS)) {
                workPool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void getQueue() {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        Set<ChatResult> queue = new LinkedHashSet<>();

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                queue.addAll(storage.getQueue());
            } catch (StorageException ex) {
                logger.error("Could not get queue from " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Iterator<ChatResult> i = queue.iterator(); i.hasNext();) {
            ChatResult c = i.next();
            if (cachedPosts.get(c.getID())) {
                i.remove();
                continue;
            }
            cachedPosts.put(c.getID(), Boolean.TRUE);
            try {
                handler.handle(c);
            } catch (Throwable ex) {
                logger.error("Could not handle post.", ex);
            }
        }

        try {
            Thread.sleep(10L * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        workPool.execute(this::getQueue);
    }

    public void conformStorage() {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        if (cachedConfig.get().getStorage().size() > 1) {
            Storage master = cachedConfig.get().getStorage().get(0);
            for (int i = 1; i < cachedConfig.get().getStorage().size(); i++) {
                cachedConfig.get().getStorage().get(i).
            }
        }
    }

    public void playerIDCreationCallback(UUID playerID, long longPlayerID, Storage callingStorage) {
        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Player created: " + playerID.toString() + " = " + longPlayerID);
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            if (storage != callingStorage) {
                try {
                    storage.setPlayerRaw(longPlayerID, playerID);
                } catch (StorageException ex) {
                    logger.error("Could not set raw player data for " + storage.getClass().getSimpleName() + ".", ex);
                }
            }
        }

        UUID messageID = UUID.randomUUID();
        cachedMessages.put(messageID, Boolean.TRUE);

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            try {
                messaging.sendPlayer(messageID, longPlayerID, playerID);
            } catch (MessagingException ex) {
                logger.error("Could not send raw player data for " + messaging.getClass().getSimpleName() + ".", ex);
            }
        }
    }

    public void levelCallback(UUID messageID, byte level, String name, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Level created/updated: " + level + " = \"" + name + "\"");
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setLevelRaw(level, name);
            } catch (StorageException ex) {
                logger.error("Could not set raw level data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendLevel(messageID, level, name);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw level data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void serverCallback(UUID messageID, long longServerID, UUID serverID, String name, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Server created/updated: " + serverID.toString() + " = \"" + name + "\"");
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setServerRaw(longServerID, serverID, name);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendServer(messageID, longServerID, serverID, name);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void playerCallback(UUID messageID, UUID playerID, long longPlayerID, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Player created: " + playerID.toString() + " = " + longPlayerID);
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setPlayerRaw(longPlayerID, playerID);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendPlayer(messageID, longPlayerID, playerID);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void postCallback(UUID messageID, long postID, long longServerID, UUID serverID, String serverName, long longPlayerID, UUID playerID, byte level, String levelName, String message, long date, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Post created: " + postID + " - \"" + message + "\"");
            logger.info("Propagating to storage & messaging");
        }

        cachedPosts.put(postID, Boolean.TRUE);
        try {
            handler.handle(new ChatResult(postID, serverID, serverName, playerID, level, levelName, message, date));
        } catch (Throwable ex) {
            logger.error("Could not handle post.", ex);
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.postRaw(postID, longServerID, longPlayerID, level, message, date);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendPost(messageID, postID, longServerID, serverID, serverName, longPlayerID, playerID, level, levelName, message, date);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }
}
