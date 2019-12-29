package me.egg82.ssc.services;

import java.util.UUID;
import me.egg82.ssc.messaging.Messaging;

public interface MessagingHandler {
    void levelCallback(UUID messageID, byte level, String name, Messaging callingMessaging);
    void serverCallback(UUID messageID, long longServerID, UUID serverID, String name, Messaging callingMessaging);
    void playerCallback(UUID messageID, UUID playerID, long longPlayerID, Messaging callingMessaging);
    void postCallback(UUID messageID, long postID, long longServerID, long longPlayerID, byte level, String message, long date, Messaging callingMessaging);
}
