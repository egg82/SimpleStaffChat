package me.egg82.ssc.core;

import java.util.Objects;
import java.util.UUID;

public class PostChatResult {
    private final long id;
    private final long longServerID;
    private final UUID serverID;
    private final String serverName;
    private final long longPlayerID;
    private final UUID playerID;
    private final byte level;
    private final String levelName;
    private final String message;
    private final long date;

    private final int hc;

    public PostChatResult(long id, long longServerID, UUID serverID, String serverName, long longPlayerID, UUID playerID, byte level, String levelName, String message, long date) {
        this.id = id;
        this.longServerID = longServerID;
        this.serverID = serverID;
        this.serverName = serverName;
        this.longPlayerID = longPlayerID;
        this.playerID = playerID;
        this.level = level;
        this.levelName = levelName;
        this.message = message;
        this.date = date;

        hc = Objects.hash(id);
    }

    public long getID() { return id; }

    public long getLongServerID() { return longServerID; }

    public UUID getServerID() { return serverID; }

    public String getServerName() { return serverName; }

    public long getLongPlayerID() { return longPlayerID; }

    public UUID getPlayerID() { return playerID; }

    public byte getLevel() { return level; }

    public String getLevelName() { return levelName; }

    public String getMessage() { return message; }

    public long getDate() { return date; }

    public ChatResult toChatResult() {
        return new ChatResult(
                id,
                serverID,
                serverName,
                playerID,
                level,
                levelName,
                message,
                date
        );
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PostChatResult)) return false;
        PostChatResult that = (PostChatResult) o;
        return id == that.id;
    }

    public int hashCode() { return hc; }
}
