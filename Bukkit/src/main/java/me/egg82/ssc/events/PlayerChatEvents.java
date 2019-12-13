package me.egg82.ssc.events;

import me.egg82.ssc.services.CollectionProvider;
import ninja.egg82.events.BukkitEventFilters;
import ninja.egg82.events.BukkitEvents;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

public class PlayerChatEvents extends EventHolder {
    public PlayerChatEvents(Plugin plugin) {
        events.add(
                BukkitEvents.subscribe(plugin, AsyncPlayerChatEvent.class, EventPriority.HIGH)
                        .filter(BukkitEventFilters.ignoreCancelled())
                        .filter(e -> CollectionProvider.getToggle().getOrDefault(e.getPlayer().getUniqueId(), Boolean.FALSE))
                        .handler(this::sendChat)
        );
    }

    private void sendChat(AsyncPlayerChatEvent event) {

    }
}
