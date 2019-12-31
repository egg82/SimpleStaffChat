package me.egg82.ssc;

import co.aikar.commands.*;
import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChainFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.*;
import java.util.logging.Level;
import me.egg82.ssc.commands.SimpleStaffChatCommand;
import me.egg82.ssc.commands.StaffChatCommand;
import me.egg82.ssc.core.LevelResult;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.events.EventHolder;
import me.egg82.ssc.events.PlayerChatEvents;
import me.egg82.ssc.events.PlayerLoginUpdateNotifyHandler;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.extended.Configuration;
import me.egg82.ssc.hooks.PlayerAnalyticsHook;
import me.egg82.ssc.hooks.PluginHook;
import me.egg82.ssc.services.BukkitPostHandler;
import me.egg82.ssc.services.GameAnalyticsErrorHandler;
import me.egg82.ssc.services.PluginMessageFormatter;
import me.egg82.ssc.services.StorageMessagingHandler;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.*;
import ninja.egg82.events.BukkitEventSubscriber;
import ninja.egg82.events.BukkitEvents;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import ninja.egg82.updater.SpigotUpdater;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SimpleStaffChat {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private ExecutorService workPool = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("SimpleStaffChat-%d").build());

    private TaskChainFactory taskFactory;
    private PaperCommandManager commandManager;

    private List<EventHolder> eventHolders = new ArrayList<>();
    private List<BukkitEventSubscriber<?>> events = new ArrayList<>();
    private List<Integer> tasks = new ArrayList<>();

    private Metrics metrics = null;

    private final Plugin plugin;
    private final boolean isBukkit;

    private CommandIssuer consoleCommandIssuer = null;

    public SimpleStaffChat(Plugin plugin) {
        isBukkit = BukkitEnvironmentUtil.getEnvironment() == BukkitEnvironmentUtil.Environment.BUKKIT;
        this.plugin = plugin;
    }

    public void onLoad() {
        if (BukkitEnvironmentUtil.getEnvironment() != BukkitEnvironmentUtil.Environment.PAPER) {
            log(Level.INFO, ChatColor.AQUA + "====================================");
            log(Level.INFO, ChatColor.YELLOW + "SimpleStaffChat runs better on Paper!");
            log(Level.INFO, ChatColor.YELLOW + "https://whypaper.emc.gs/");
            log(Level.INFO, ChatColor.AQUA + "====================================");
        }

        if (BukkitVersionUtil.getGameVersion().startsWith("1.8")) {
            log(Level.INFO, ChatColor.AQUA + "====================================");
            log(Level.INFO, ChatColor.DARK_RED + "DEAR LORD why are you on 1.8???");
            log(Level.INFO, ChatColor.DARK_RED + "Have you tried ViaVersion or ProtocolSupport lately?");
            log(Level.INFO, ChatColor.AQUA + "====================================");
        }
    }

    public void onEnable() {
        GameAnalyticsErrorHandler.open(ServerIDUtil.getID(new File(plugin.getDataFolder(), "stats-id.txt")), plugin.getDescription().getVersion(), Bukkit.getVersion());

        taskFactory = BukkitTaskChainFactory.create(plugin);
        commandManager = new PaperCommandManager(plugin);
        commandManager.enableUnstableAPI("help");

        consoleCommandIssuer = commandManager.getCommandIssuer(plugin.getServer().getConsoleSender());

        loadLanguages();
        loadServices();
        loadCommands();
        loadEvents();
        loadTasks();
        loadHooks();
        loadMetrics();

        int numEvents = events.size();
        for (EventHolder eventHolder : eventHolders) {
            numEvents += eventHolder.numEvents();
        }

        consoleCommandIssuer.sendInfo(Message.GENERAL__ENABLED);
        consoleCommandIssuer.sendInfo(Message.GENERAL__LOAD,
                "{version}", plugin.getDescription().getVersion(),
                "{commands}", String.valueOf(commandManager.getRegisteredRootCommands().size()),
                "{events}", String.valueOf(numEvents),
                "{tasks}", String.valueOf(tasks.size())
        );

        workPool.execute(this::checkUpdate);
    }

    public void onDisable() {
        workPool.shutdown();
        try {
            if (!workPool.awaitTermination(4L, TimeUnit.SECONDS)) {
                workPool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        taskFactory.shutdown(4, TimeUnit.SECONDS);
        commandManager.unregisterCommands();

        for (int task : tasks) {
            Bukkit.getScheduler().cancelTask(task);
        }
        tasks.clear();

        for (EventHolder eventHolder : eventHolders) {
            eventHolder.cancel();
        }
        eventHolders.clear();
        for (BukkitEventSubscriber<?> event : events) {
            event.cancel();
        }
        events.clear();

        unloadHooks();
        unloadServices();

        consoleCommandIssuer.sendInfo(Message.GENERAL__DISABLED);

        GameAnalyticsErrorHandler.close();
    }

    private void loadLanguages() {
        BukkitLocales locales = commandManager.getLocales();

        try {
            for (Locale locale : Locale.getAvailableLocales()) {
                Optional<File> localeFile = LanguageFileUtil.getLanguage(plugin, locale);
                if (localeFile.isPresent()) {
                    commandManager.addSupportedLanguage(locale);
                    locales.loadYamlLanguageFile(localeFile.get(), locale);
                }
            }
        } catch (IOException | InvalidConfigurationException ex) {
            logger.error(ex.getMessage(), ex);
        }

        locales.loadLanguages();
        commandManager.usePerIssuerLocale(true, true);

        commandManager.setFormat(MessageType.ERROR, new PluginMessageFormatter(commandManager, Message.GENERAL__HEADER));
        commandManager.setFormat(MessageType.INFO, new PluginMessageFormatter(commandManager, Message.GENERAL__HEADER));
        commandManager.setFormat(MessageType.ERROR, ChatColor.DARK_RED, ChatColor.YELLOW, ChatColor.AQUA, ChatColor.WHITE);
        commandManager.setFormat(MessageType.INFO, ChatColor.WHITE, ChatColor.YELLOW, ChatColor.AQUA, ChatColor.GREEN, ChatColor.RED, ChatColor.GOLD, ChatColor.BLUE, ChatColor.GRAY);
    }

    private void loadServices() {
        StorageMessagingHandler handler = new StorageMessagingHandler(new BukkitPostHandler(plugin, commandManager));
        ServiceLocator.register(handler);
        ConfigurationFileUtil.reloadConfig(plugin, commandManager, handler, handler);

        // TODO: get all toggle states from other servers/network messaging

        ServiceLocator.register(new SpigotUpdater(plugin, 73919));
    }

    private void loadCommands() {
        commandManager.getCommandCompletions().registerCompletion("storage", c -> {
            String lower = c.getInput().toLowerCase().replace(" ", "_");
            Set<String> storage = new LinkedHashSet<>();
            Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
            if (!cachedConfig.isPresent()) {
                logger.error("Cached config could not be fetched.");
                return ImmutableList.copyOf(storage);
            }
            for (Storage s : cachedConfig.get().getStorage()) {
                String ss = s.getClass().getSimpleName();
                if (ss.toLowerCase().startsWith(lower)) {
                    storage.add(ss);
                }
            }
            return ImmutableList.copyOf(storage);
        });

        commandManager.getCommandCompletions().registerCompletion("level", c -> {
            String lower = c.getInput().toLowerCase().replace(" ", "_");
            Set<String> retVal = new LinkedHashSet<>();
            Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
            if (!cachedConfig.isPresent()) {
                logger.error("Cached config could not be fetched.");
                return ImmutableList.copyOf(retVal);
            }
            List<LevelResult> levels = null;
            for (Storage s : cachedConfig.get().getStorage()) {
                try {
                    levels = s.getLevels();
                    break;
                } catch (StorageException ex) {
                    logger.error("Could not get levels from " + s.getClass().getSimpleName() + ".", ex);
                }
            }
            if (levels != null) {
                for (LevelResult level : levels) {
                    if (!lower.isEmpty() && String.valueOf(level.getLevel()).startsWith(lower)) {
                        retVal.add(String.valueOf(level.getLevel()));
                    }
                    if (level.getName().toLowerCase().startsWith(lower)) {
                        retVal.add(level.getName());
                    }
                }
            }
            return ImmutableList.copyOf(retVal);
        });

        commandManager.getCommandCompletions().registerCompletion("subcommand", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> commands = new LinkedHashSet<>();
            SetMultimap<String, RegisteredCommand> subcommands = commandManager.getRootCommand("simplestaffchat").getSubCommands();
            for (Map.Entry<String, RegisteredCommand> kvp : subcommands.entries()) {
                if (!kvp.getValue().isPrivate() && (lower.isEmpty() || kvp.getKey().toLowerCase().startsWith(lower)) && kvp.getValue().getCommand().indexOf(' ') == -1) {
                    commands.add(kvp.getValue().getCommand());
                }
            }
            return ImmutableList.copyOf(commands);
        });

        commandManager.registerCommand(new SimpleStaffChatCommand(plugin, taskFactory, commandManager));
        commandManager.registerCommand(new StaffChatCommand(taskFactory));
    }

    private void loadEvents() {
        events.add(BukkitEvents.subscribe(plugin, PlayerLoginEvent.class, EventPriority.LOW).handler(e -> new PlayerLoginUpdateNotifyHandler(plugin, commandManager).accept(e)));
        eventHolders.add(new PlayerChatEvents(plugin, commandManager));
    }

    private void loadTasks() { }

    private void loadHooks() {
        PluginManager manager = plugin.getServer().getPluginManager();

        if (manager.getPlugin("Plan") != null) {
            consoleCommandIssuer.sendInfo(Message.GENERAL__HOOK_ENABLE, "{plugin}", "Plan");
            ServiceLocator.register(new PlayerAnalyticsHook());
        } else {
            consoleCommandIssuer.sendInfo(Message.GENERAL__HOOK_DISABLE, "{plugin}", "Plan");
        }
    }

    private void loadMetrics() {
        metrics = new Metrics(plugin);
    }

    private void checkUpdate() {
        Optional<Configuration> config = ConfigUtil.getConfig();
        if (!config.isPresent()) {
            return;
        }

        SpigotUpdater updater;
        try {
            updater = ServiceLocator.get(SpigotUpdater.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        if (!config.get().getNode("update", "check").getBoolean(true)) {
            return;
        }

        updater.isUpdateAvailable().thenAccept(v -> {
            if (!v) {
                return;
            }

            try {
                consoleCommandIssuer.sendInfo(Message.GENERAL__UPDATE, "{version}", updater.getLatestVersion().get());
            } catch (ExecutionException ex) {
                logger.error(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                logger.error(ex.getMessage(), ex);
                Thread.currentThread().interrupt();
            }
        });

        try {
            Thread.sleep(60L * 60L * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        try {
            workPool.execute(this::checkUpdate);
        } catch (RejectedExecutionException ignored) { }
    }

    private void unloadHooks() {
        Set<? extends PluginHook> hooks = ServiceLocator.remove(PluginHook.class);
        for (PluginHook hook : hooks) {
            hook.cancel();
        }
    }

    public void unloadServices() {
        Optional<StorageMessagingHandler> storageMessagingHandler;
        try {
            storageMessagingHandler = ServiceLocator.getOptional(StorageMessagingHandler.class);
        } catch (IllegalAccessException | InstantiationException ex) {
            storageMessagingHandler = Optional.empty();
        }
        storageMessagingHandler.ifPresent(StorageMessagingHandler::close);
    }

    private void log(Level level, String message) {
        plugin.getServer().getLogger().log(level, (isBukkit) ? ChatColor.stripColor(message) : message);
    }
}
