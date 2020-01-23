package me.egg82.ssc.events;

import java.util.ArrayList;
import java.util.List;
import me.egg82.ssc.StaffChatAPI;
import ninja.egg82.events.BukkitEventSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EventHolder {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final List<BukkitEventSubscriber<?>> events = new ArrayList<>();

    protected final StaffChatAPI api = StaffChatAPI.getInstance();

    public final int numEvents() { return events.size(); }

    public final void cancel() {
        for (BukkitEventSubscriber<?> event : events) {
            event.cancel();
        }
    }
}
