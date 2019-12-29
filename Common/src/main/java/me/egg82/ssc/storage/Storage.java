package me.egg82.ssc.storage;

import java.util.Set;
import java.util.UUID;
import me.egg82.ssc.core.*;

public interface Storage {
    void close();
    boolean isClosed();

    Set<ChatResult> getQueue() throws StorageException;
    default Set<ChatResult> getByPlayer(UUID playerID) throws StorageException { return getByPlayer(playerID, 1); }
    Set<ChatResult> getByPlayer(UUID playerID, int days) throws StorageException;
    default PostChatResult post(UUID playerID, String message) throws StorageException { return post(playerID, (byte) 1, message); }
    PostChatResult post(UUID playerID, byte level, String message) throws StorageException;

    void setLevelRaw(byte level, String name) throws StorageException;
    void setServerRaw(long longServerID, UUID serverID, String name) throws StorageException;
    void setPlayerRaw(long longPlayerID, UUID playerID) throws StorageException;
    void postRaw(long postID, long longServerID, long longPlayerID, byte level, String message, long date) throws StorageException;

    void setLevel(byte level, String name) throws StorageException;
    void setServerName(String name) throws StorageException;

    long getLongPlayerID(UUID playerID);

    Set<LevelResult> dumpLevels() throws StorageException;
    void loadLevels(Set<LevelResult> levels) throws StorageException;

    Set<ServerResult> dumpServers() throws StorageException;
    void loadServers(Set<ServerResult> servers) throws StorageException;

    Set<PlayerResult> dumpPlayers(long begin, int size) throws StorageException;
    void loadPlayers(Set<PlayerResult> players, boolean truncate) throws StorageException;

    Set<RawChatResult> dumpChat(long begin, int size) throws StorageException;
    void loadChat(Set<RawChatResult> chat, boolean truncate) throws StorageException;
}
