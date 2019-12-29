package me.egg82.ssc.services;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CollectionProvider {
    private CollectionProvider() {}

    private static ConcurrentMap<UUID, Byte> toggled = new ConcurrentHashMap<>();
    public static ConcurrentMap<UUID, Byte> getToggled() { return toggled; }
}
