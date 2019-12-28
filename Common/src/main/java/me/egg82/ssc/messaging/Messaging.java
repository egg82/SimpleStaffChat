package me.egg82.ssc.messaging;

import java.util.UUID;

public interface Messaging {
    void sendLevelRaw(byte level, String name) throws MessagingException;
    void sendServerRaw(long longServerID, UUID serverID, String name) throws MessagingException;
    void sendPlayerRaw(long longPlayerID, UUID playerID) throws MessagingException;
    void sendPostRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws MessagingException;
}
