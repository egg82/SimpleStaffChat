package me.egg82.ssc.services;

import co.aikar.commands.CommandManager;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import me.egg82.ssc.core.ChatResult;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.extended.PostHandler;
import me.egg82.ssc.services.lookup.PlayerInfo;
import me.egg82.ssc.services.lookup.PlayerLookup;
import me.egg82.ssc.utils.ConfigUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BukkitPostHandler implements PostHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Plugin plugin;
    private final CommandManager commandManager;

    private final UUID serverID = new UUID(0L, 0L);

    public BukkitPostHandler(Plugin plugin, CommandManager commandManager) {
        this.plugin = plugin;
        this.commandManager = commandManager;
    }

    public void handle(ChatResult chat) {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        String formattedMessage = format(chat, cachedConfig.get().getChatFormat(), cachedConfig.get().getAllowColors());
        CollectionProvider.getFormattedMessages().put(formattedMessage, Boolean.TRUE);
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            commandManager.getCommandIssuer(Bukkit.getConsoleSender()).sendMessage(formattedMessage);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("ssc.level." + chat.getLevel())) {
                    commandManager.getCommandIssuer(player).sendMessage(formattedMessage);
                }
            }
        }, 1L);
    }

    public void toggle(UUID playerID, byte level) { CollectionProvider.getToggled().put(playerID, level); }

    private String format(ChatResult chat, String format, boolean allowColors) {
        String message = allowColors ? chat.getMessage() : ChatColor.stripColor(chat.getMessage());
        return format.
                replace("{server}", chat.getServerName())
                .replace("{level}", chat.getLevelName())
                .replace("{player}", getPlayerName(chat.getPlayerID()))
                .replace("{message}", message);
    }

    private String getPlayerName(UUID uuid) {
        if (uuid.equals(serverID)) {
            return "CONSOLE";
        }

        PlayerInfo info;
        try {
            info = PlayerLookup.get(uuid);
        } catch (IOException ex) {
            logger.warn("Could not fetch player name. (rate-limited?)", ex);
            return uuid.toString();
        }
        return info.getName();
    }
}
