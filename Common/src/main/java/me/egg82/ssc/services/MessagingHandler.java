package me.egg82.ssc.services;

import java.util.UUID;
import me.egg82.ssc.messaging.Messaging;

public interface MessagingHandler {
    void playerIDCreationCallback(UUID playerID, long longPlayerID, Messaging callingMessaging);
}
