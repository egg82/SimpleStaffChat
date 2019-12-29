package me.egg82.ssc.core;

import java.util.Objects;

public class LevelResult {
    private final byte level;
    private final String name;

    private final int hc;

    public LevelResult(byte level, String name) {
        this.level = level;
        this.name = name;

        hc = Objects.hash(level);
    }

    public byte getLevel() { return level; }

    public String getName() { return name; }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LevelResult)) return false;
        LevelResult that = (LevelResult) o;
        return level == that.level;
    }

    public int hashCode() { return hc; }
}
