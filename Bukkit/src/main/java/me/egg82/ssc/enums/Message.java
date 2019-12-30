package me.egg82.ssc.enums;

import co.aikar.locales.MessageKey;
import co.aikar.locales.MessageKeyProvider;

public enum Message implements MessageKeyProvider {
    GENERAL__HEADER,
    GENERAL__ENABLED,
    GENERAL__DISABLED,
    GENERAL__LOAD,
    GENERAL__HOOK_ENABLE,
    GENERAL__HOOK_DISABLE,
    GENERAL__UPDATE,

    ERROR__INTERNAL,
    ERROR__NO_PERMS,
    ERROR__LEVEL_NOT_FOUND,
    ERROR__NO_CONSOLE,

    IMPORT__SAME_STORAGE,
    IMPORT__NO_MASTER,
    IMPORT__NO_SLAVE,
    IMPORT__LEVELS,
    IMPORT__SERVERS,
    IMPORT__PLAYERS,
    IMPORT__CHAT,
    IMPORT__BEGIN,
    IMPORT__END,

    CHAT__LEVEL_CHANGED,
    CHAT__LEVEL_CLEARED,

    LEVEL__NO_LEVEL,
    LEVEL__NO_NAME,
    LEVEL__BEGIN,
    LEVEL__END,

    RELOAD__BEGIN,
    RELOAD__END;

    private final MessageKey key = MessageKey.of(name().toLowerCase().replace("__", "."));
    public MessageKey getMessageKey() { return key; }
}
