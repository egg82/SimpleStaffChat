package me.egg82.ssc.services;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CollectionProvider {
    private CollectionProvider() {}

    private static ConcurrentMap<UUID, Boolean> toggle = new ConcurrentHashMap<>();
    public static ConcurrentMap<UUID, Boolean> getToggle() { return toggle; }
}
