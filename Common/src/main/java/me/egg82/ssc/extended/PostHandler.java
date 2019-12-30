package me.egg82.ssc.extended;

import java.util.UUID;
import me.egg82.ssc.core.ChatResult;

public interface PostHandler {
    void handle(ChatResult post);

    void toggle(UUID playerID, byte level);
}
