package me.egg82.ssc.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class CollectionProvider {
    private CollectionProvider() {}

    private static ConcurrentMap<UUID, Byte> toggled = new ConcurrentHashMap<>();
    public static ConcurrentMap<UUID, Byte> getToggled() { return toggled; }

    public static ConcurrentMap<String, Boolean> formattedMessages = Caffeine.newBuilder().expireAfterAccess(1L, TimeUnit.MINUTES).<String, Boolean>build().asMap();
    public static ConcurrentMap<String, Boolean> getFormattedMessages() { return formattedMessages; }
}
