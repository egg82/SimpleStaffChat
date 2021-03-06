package me.egg82.ssc.hooks;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ExtensionService;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import me.egg82.ssc.APIException;
import me.egg82.ssc.StaffChatAPI;
import ninja.egg82.events.BukkitEvents;
import ninja.egg82.service.ServiceLocator;
import org.bukkit.event.EventPriority;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerAnalyticsHook implements PluginHook {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final CapabilityService capabilities;

    public static void create(Plugin plugin, Plugin plan) {
        if (!plan.isEnabled()) {
            BukkitEvents.subscribe(plugin, PluginEnableEvent.class, EventPriority.MONITOR)
                    .expireIf(e -> e.getPlugin().getName().equals("Plan"))
                    .filter(e -> e.getPlugin().getName().equals("Plan"))
                    .handler(e -> ServiceLocator.register(new PlayerAnalyticsHook()));
            return;
        }
        ServiceLocator.register(new PlayerAnalyticsHook());
    }

    private PlayerAnalyticsHook() {
        capabilities = CapabilityService.getInstance();

        if (isCapabilityAvailable("DATA_EXTENSION_VALUES") && isCapabilityAvailable("DATA_EXTENSION_TABLES")) {
            try {
                ExtensionService.getInstance().register(new Data());
            } catch (NoClassDefFoundError ex) {
                // Plan not installed
                logger.error("Plan is not installed.", ex);
            } catch (IllegalStateException ex) {
                // Plan not enabled
                logger.error("Plan is not enabled.", ex);
            } catch (IllegalArgumentException ex) {
                // DataExtension impl error
                logger.error("DataExtension implementation exception.", ex);
            }
        }
    }

    public void cancel() { }

    private boolean isCapabilityAvailable(String capability) {
        try {
            return capabilities.hasCapability(capability);
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    @PluginInfo(
            name = "SimpleStaffChat",
            iconName = "comments",
            iconFamily = Family.REGULAR,
            color = Color.CYAN
    )
    class Data implements DataExtension {
        private final StaffChatAPI api = StaffChatAPI.getInstance();
        private final CallEvents[] events = new CallEvents[] { CallEvents.SERVER_PERIODICAL, CallEvents.SERVER_EXTENSION_REGISTER };

        private Data() { }

        @NumberProvider(
                text = "Sent Messages",
                description = "Number of messages sent.",
                priority = 2,
                iconName = "exchange-alt",
                iconFamily = Family.SOLID,
                iconColor = Color.NONE,
                format = FormatType.NONE
        )
        public long getNumSentMessages() {
            try {
                return api.getNumSentMessages();
            } catch (APIException ex) {
                logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
            }
            return 0L;
        }

        @NumberProvider(
                text = "Received Messages",
                description = "Number of messages received.",
                priority = 1,
                iconName = "exchange-alt",
                iconFamily = Family.SOLID,
                iconColor = Color.NONE,
                format = FormatType.NONE
        )
        public long getNumReceivedMessages() {
            try {
                return api.getNumReceivedMessages();
            } catch (APIException ex) {
                logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
            }
            return 0L;
        }

        public CallEvents[] callExtensionMethodsOn() { return events; }
    }
}
