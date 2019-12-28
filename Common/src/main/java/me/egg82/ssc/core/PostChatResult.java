package me.egg82.ssc.core;

import java.util.Objects;

public class PostChatResult {
    private final long id;
    private final long longServerID;
    private final long longPlayerID;
    private final byte level;
    private final String message;
    private final long date;

    private final int hc;

    public PostChatResult(long id, long longServerID, long longPlayerID, byte level, String message, long date) {
        this.id = id;
        this.longServerID = longServerID;
        this.longPlayerID = longPlayerID;
        this.level = level;
        this.message = message;
        this.date = date;

        hc = Objects.hash(id);
    }

    public long getID() { return id; }

    public long getLongServerID() { return longServerID; }

    public long getLongPlayerID() { return longPlayerID; }

    public byte getLevel() { return level; }

    public String getMessage() { return message; }

    public long getDate() { return date; }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PostChatResult)) return false;
        PostChatResult that = (PostChatResult) o;
        return id == that.id;
    }

    public int hashCode() { return hc; }
}
