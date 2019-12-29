package me.egg82.ssc.messaging;

import java.util.UUID;

public interface Messaging {
    void close();
    boolean isClosed();

    void sendLevel(UUID messageID, byte level, String name) throws MessagingException;
    void sendServer(UUID messageID, long longServerID, UUID serverID, String name) throws MessagingException;
    void sendPlayer(UUID messageID, long longPlayerID, UUID playerID) throws MessagingException;
    void sendPost(UUID messageID, long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws MessagingException;
}
