package me.egg82.ssc.events;

import co.aikar.commands.CommandManager;
import java.util.Optional;
import me.egg82.ssc.APIException;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.services.CollectionProvider;
import me.egg82.ssc.utils.ConfigUtil;
import ninja.egg82.events.BukkitEventFilters;
import ninja.egg82.events.BukkitEvents;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public class PlayerChatEvents extends EventHolder {
    private final CommandManager commandManager;

    public PlayerChatEvents(Plugin plugin, CommandManager commandManager) {
        this.commandManager = commandManager;

        events.add(
                BukkitEvents.subscribe(plugin, AsyncPlayerChatEvent.class, EventPriority.HIGH)
                        .filter(BukkitEventFilters.ignoreCancelled())
                        .handler(this::sendChat)
        );

        events.add(
                BukkitEvents.subscribe(plugin, PlayerQuitEvent.class, EventPriority.LOW)
                        .filter(e -> !hasMessaging())
                        .handler(this::removeToggle)
        );
    }

    private void sendChat(AsyncPlayerChatEvent event) {
        byte level = CollectionProvider.getToggled().getOrDefault(event.getPlayer().getUniqueId(), (byte) -1);
        if (level == -1) {
            return;
        }

        event.setCancelled(true);

        if (!event.getPlayer().hasPermission("ssc.level." + level)) {
            CollectionProvider.getToggled().remove(event.getPlayer().getUniqueId());
            commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__NO_PERMS);
            return;
        }

        try {
            api.sendChat(event.getPlayer().getUniqueId(), level, event.getMessage());
        } catch (APIException ex) {
            logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
            commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__INTERNAL);
        }
    }

    private boolean hasMessaging() {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return false;
        }

        return !cachedConfig.get().getMessaging().isEmpty();
    }

    private void removeToggle(PlayerQuitEvent event) { CollectionProvider.getToggled().remove(event.getPlayer().getUniqueId()); }
}
