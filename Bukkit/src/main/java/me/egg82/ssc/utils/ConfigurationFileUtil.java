package me.egg82.ssc.utils;

import co.aikar.commands.CommandManager;
import com.google.common.reflect.TypeToken;
import java.util.*;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.extended.Configuration;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
import me.egg82.ssc.messaging.RabbitMQ;
import me.egg82.ssc.services.MessagingHandler;
import me.egg82.ssc.services.StorageHandler;
import me.egg82.ssc.storage.MySQL;
import me.egg82.ssc.storage.SQLite;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import ninja.egg82.service.ServiceLocator;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;

import java.io.*;
import java.nio.file.Files;
import java.text.DecimalFormat;

public class ConfigurationFileUtil {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationFileUtil.class);

    private static final DecimalFormat format = new DecimalFormat("##0.################");

    private ConfigurationFileUtil() {}

    public static void reloadConfig(Plugin plugin, CommandManager commandManager, StorageHandler storageHandler, MessagingHandler messagingHandler) {
        Configuration config;
        try {
            config = getConfig(plugin, "config.yml", new File(plugin.getDataFolder(), "config.yml"));
        } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        boolean debug = config.getNode("debug").getBoolean(false);

        if (!debug) {
            Reflections.log = null;
        }

        if (debug) {
            logger.info(LogUtil.getHeading() + ChatColor.YELLOW + "Debug " + ChatColor.WHITE + "enabled");
        }

        UUID serverID = ServerIDUtil.getID(new File(plugin.getDataFolder(), "stats-id.txt"));
        String serverName = ServerNameUtil.getName(new File(plugin.getDataFolder(), "server-name.txt"));

        List<Storage> storage;
        try {
            storage = getStorage(plugin, config.getNode("storage", "engines"), new PoolSettings(config.getNode("storage", "settings")), debug, serverID, serverName, config.getNode("storage", "order").getList(TypeToken.of(String.class)), storageHandler);
        } catch (ObjectMappingException ex) {
            logger.error(ex.getMessage(), ex);
            storage = new ArrayList<>();
        }

        if (debug) {
            for (Storage s : storage) {
                logger.info(LogUtil.getHeading() + ChatColor.YELLOW + "Added storage: " + ChatColor.WHITE + s.getClass().getSimpleName());
            }
        }

        List<Messaging> messaging;
        try {
            messaging = getMessaging(config.getNode("messaging", "engines"), new PoolSettings(config.getNode("messaging", "settings")), debug, serverID, config.getNode("messaging", "order").getList(TypeToken.of(String.class)), messagingHandler);
        } catch (ObjectMappingException ex) {
            logger.error(ex.getMessage(), ex);
            messaging = new ArrayList<>();
        }

        if (debug) {
            for (Messaging m : messaging) {
                logger.info(LogUtil.getHeading() + ChatColor.YELLOW + "Added messaging: " + ChatColor.WHITE + m.getClass().getSimpleName());
            }
        }

        String chatFormat = config.getNode("chat", "format").getString("&6[&r{server}&r&6] [&r{level}&r&6] &b{player} &7>>&r {message}");
        if (debug) {
            logger.info(LogUtil.getHeading() + ChatColor.YELLOW + "Format: " + ChatColor.RESET + chatFormat);
        }

        boolean allowColors = config.getNode("chat", "allow-codes").getBoolean(true);
        if (debug) {
            logger.info(LogUtil.getHeading() + ChatColor.YELLOW + (allowColors ? "Using color/format codes." : "Restricting color/format codes."));
        }

        CachedConfigValues cachedValues = CachedConfigValues.builder()
                .debug(debug)
                .storage(storage)
                .messaging(messaging)
                .chatFormat(chatFormat)
                .allowColors(allowColors)
                .build();

        ConfigUtil.setConfiguration(config, cachedValues);

        ServiceLocator.register(config);
        ServiceLocator.register(cachedValues);
    }

    public static Configuration getConfig(Plugin plugin, String resourcePath, File fileOnDisk) throws IOException {
        File parentDir = fileOnDisk.getParentFile();
        if (parentDir.exists() && !parentDir.isDirectory()) {
            Files.delete(parentDir.toPath());
        }
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Could not create parent directory structure.");
            }
        }
        if (fileOnDisk.exists() && fileOnDisk.isDirectory()) {
            Files.delete(fileOnDisk.toPath());
        }

        if (!fileOnDisk.exists()) {
            try (InputStreamReader reader = new InputStreamReader(plugin.getResource(resourcePath));
                 BufferedReader in = new BufferedReader(reader);
                 FileWriter writer = new FileWriter(fileOnDisk);
                 BufferedWriter out = new BufferedWriter(writer)) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.write(line + System.lineSeparator());
                }
            }
        }

        ConfigurationLoader<ConfigurationNode> loader = YAMLConfigurationLoader.builder().setFlowStyle(DumperOptions.FlowStyle.BLOCK).setIndent(2).setFile(fileOnDisk).build();
        ConfigurationNode root = loader.load(ConfigurationOptions.defaults().setHeader("Comments are gone because update :(. Click here for new config + comments: https://www.spigotmc.org/resources/simplestaffchat.73919/"));
        Configuration config = new Configuration(root);
        ConfigurationVersionUtil.conformVersion(loader, config, fileOnDisk);

        return config;
    }

    private static List<Storage> getStorage(Plugin plugin, ConfigurationNode enginesNode, PoolSettings settings, boolean debug, UUID serverID, String serverName, List<String> names, StorageHandler handler) {
        List<Storage> retVal = new ArrayList<>();

        for (String name : names) {
            name = name.toLowerCase();
            switch (name) {
                case "mysql": {
                    if (!enginesNode.getNode(name, "enabled").getBoolean()) {
                        if (debug) {
                            logger.info(LogUtil.getHeading() + ChatColor.DARK_RED + name + " is disabled. Removing.");
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.getNode(name, "connection");
                    String options = connectionNode.getNode("options").getString("useSSL=false&useUnicode=true&characterEncoding=utf8");
                    if (options.length() > 0 && options.charAt(0) == '?') {
                        options = options.substring(1);
                    }
                    AddressPort url = new AddressPort("storage.engines." + name + ".connection.address", connectionNode.getNode("address").getString("127.0.0.1:3306"), 3306);
                    try {
                        retVal.add(
                                MySQL.builder(serverID, serverName, handler)
                                        .url(url.address, url.port, connectionNode.getNode("database").getString("simple_staff_chat"), connectionNode.getNode("prefix").getString("ssc_"))
                                        .credentials(connectionNode.getNode("username").getString(""), connectionNode.getNode("password").getString(""))
                                        .options(options)
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, settings.timeout)
                                        .build()
                        );
                    } catch (IOException | StorageException ex) {
                        logger.error("Could not create MySQL instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if (!enginesNode.getNode(name, "enabled").getBoolean()) {
                        if (debug) {
                            logger.info(LogUtil.getHeading() + ChatColor.DARK_RED + name + " is disabled. Removing.");
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.getNode(name, "connection");
                    AddressPort url = new AddressPort("storage.engines." + name + ".connection.address", connectionNode.getNode("address").getString("127.0.0.1:6379"), 6379);
                    try {
                        retVal.add(
                                me.egg82.ssc.storage.Redis.builder(serverID, serverName, handler)
                                        .url(url.address, url.port, connectionNode.getNode("prefix").getString("ssc_"))
                                        .credentials(connectionNode.getNode("password").getString(""))
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, (int) settings.timeout)
                                        .build()
                        );
                    } catch (StorageException ex) {
                        logger.error("Could not create Redis instance.", ex);
                    }
                    break;
                }
                case "sqlite": {
                    if (!enginesNode.getNode(name, "enabled").getBoolean()) {
                        if (debug) {
                            logger.info(LogUtil.getHeading() + ChatColor.DARK_RED + name + " is disabled. Removing.");
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.getNode(name, "connection");
                    String options = connectionNode.getNode("options").getString("useUnicode=true&characterEncoding=utf8");
                    if (options.length() > 0 && options.charAt(0) == '?') {
                        options = options.substring(1);
                    }
                    String file = connectionNode.getNode("file").getString("simple_staff_chat.db");
                    try {
                        retVal.add(
                                SQLite.builder(serverID, serverName, handler)
                                        .file(new File(plugin.getDataFolder(), file), connectionNode.getNode("prefix").getString("ssc_"))
                                        .options(options)
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, settings.timeout)
                                        .build()
                        );
                    } catch (IOException | StorageException ex) {
                        logger.error("Could not create SQLite instance.", ex);
                    }
                    break;
                }
                default: {
                    logger.warn("Unknown storage type: \"" + name + "\"");
                    break;
                }
            }
        }

        return retVal;
    }

    private static List<Messaging> getMessaging(ConfigurationNode enginesNode, PoolSettings settings, boolean debug, UUID serverID, List<String> names, MessagingHandler handler) {
        List<Messaging> retVal = new ArrayList<>();

        for (String name : names) {
            name = name.toLowerCase();
            switch (name) {
                case "rabbitmq": {
                    if (!enginesNode.getNode(name, "enabled").getBoolean()) {
                        if (debug) {
                            logger.info(LogUtil.getHeading() + ChatColor.DARK_RED + name + " is disabled. Removing.");
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.getNode(name, "connection");
                    AddressPort url = new AddressPort("messaging.engines." + name + ".connection.address", connectionNode.getNode("address").getString("127.0.0.1:6379"), 6379);
                    try {
                        retVal.add(
                                RabbitMQ.builder(serverID, handler)
                                        .url(url.address, url.port, connectionNode.getNode("v-host").getString("/"))
                                        .credentials(connectionNode.getNode("username").getString("guest"), connectionNode.getNode("password").getString("guest"))
                                        .timeout((int) settings.timeout)
                                        .build()
                        );
                    } catch (MessagingException ex) {
                        logger.error("Could not create RabbitMQ instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if (!enginesNode.getNode(name, "enabled").getBoolean()) {
                        if (debug) {
                            logger.info(LogUtil.getHeading() + ChatColor.DARK_RED + name + " is disabled. Removing.");
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.getNode(name, "connection");
                    AddressPort url = new AddressPort("messaging.engines." + name + ".connection.address", connectionNode.getNode("address").getString("127.0.0.1:6379"), 6379);
                    try {
                        retVal.add(
                                me.egg82.ssc.messaging.Redis.builder(serverID, handler)
                                        .url(url.address, url.port)
                                        .credentials(connectionNode.getNode("password").getString(""))
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, (int) settings.timeout)
                                        .build()
                        );
                    } catch (MessagingException ex) {
                        logger.error("Could not create Redis instance.", ex);
                    }
                    break;
                }
                default: {
                    logger.warn("Unknown messaging type: \"" + name + "\"");
                    break;
                }
            }
        }

        return retVal;
    }

    private static class AddressPort {
        private String address;
        private int port;

        public AddressPort(String node, String raw, int defaultPort) {
            String address = raw;
            int portIndex = address.indexOf(':');
            int port;
            if (portIndex > -1) {
                port = Integer.parseInt(address.substring(portIndex + 1));
                address = address.substring(0, portIndex);
            } else {
                logger.warn(node + " port is an unknown value. Using default value.");
                port = defaultPort;
            }

            this.address = address;
            this.port = port;
        }

        public String getAddress() { return address; }

        public int getPort() { return port; }
    }

    private static class PoolSettings {
        private int minPoolSize;
        private int maxPoolSize;
        private long maxLifetime;
        private long timeout;

        public PoolSettings(ConfigurationNode settingsNode) {
            minPoolSize = settingsNode.getNode("min-idle").getInt();
            maxPoolSize = settingsNode.getNode("max-pool-size").getInt();
            maxLifetime = settingsNode.getNode("max-lifetime").getLong();
            timeout = settingsNode.getNode("timeout").getLong();
        }

        public int getMinPoolSize() { return minPoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }

        public long getMaxLifetime() { return maxLifetime; }

        public long getTimeout() { return timeout; }
    }
}
