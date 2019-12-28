package me.egg82.ssc.services;

import java.util.UUID;
import me.egg82.ssc.storage.Storage;

public interface StorageHandler {
    void playerIDCreationCallback(UUID playerID, long longPlayerID, Storage callingStorage);
}
