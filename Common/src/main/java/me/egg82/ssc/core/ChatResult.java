package me.egg82.ssc.core;

import java.util.Objects;
import java.util.UUID;

public class ChatResult {
    private final long id;
    private final UUID serverID;
    private final String serverName;
    private final UUID playerID;
    private final byte  level;
    private final String levelName;
    private final String message;
    private final long dateTime;

    private final int hc;

    public ChatResult(long id, UUID serverID, String serverName, UUID playerID, byte level, String levelName, String message, long dateTime) {
        this.id = id;
        this.serverID = serverID;
        this.serverName = serverName;
        this.playerID = playerID;
        this.level = level;
        this.levelName = levelName;
        this.message = message;
        this.dateTime = dateTime;

        hc = Objects.hash(id);
    }

    public long getID() { return id; }

    public UUID getServerID() { return serverID; }

    public String getServerName() { return serverName; }

    public UUID getPlayerID() { return playerID; }

    public byte getLevel() { return level; }

    public String getLevelName() { return levelName; }

    public String getMessage() { return message; }

    public long getDateTime() { return dateTime; }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatResult)) return false;
        ChatResult that = (ChatResult) o;
        return id == that.id;
    }

    public int hashCode() { return hc; }
}
