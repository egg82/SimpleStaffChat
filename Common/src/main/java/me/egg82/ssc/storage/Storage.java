package me.egg82.ssc.storage;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import me.egg82.ssc.core.ChatResult;
import me.egg82.ssc.core.PostChatResult;

public interface Storage {
    Set<ChatResult> getQueue() throws SQLException;
    default Set<ChatResult> getByPlayer(UUID playerID) throws SQLException { return getByPlayer(playerID, 1); }
    Set<ChatResult> getByPlayer(UUID playerID, int days) throws SQLException;
    default PostChatResult post(UUID playerID, String message) throws SQLException { return post(playerID, (byte) 1, message); }
    PostChatResult post(UUID playerID, byte level, String message) throws SQLException;

    void setLevelRaw(byte level, String name) throws SQLException;
    void setServerRaw(long longServerID, UUID serverID, String name) throws SQLException;
    void setPlayerRaw(long longPlayerID, UUID playerID) throws SQLException;
    void postRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws SQLException;

    void setLevel(byte level, String name) throws SQLException;
    void setServerName(String name) throws SQLException;

    long getLongPlayerID(UUID playerID);
}
