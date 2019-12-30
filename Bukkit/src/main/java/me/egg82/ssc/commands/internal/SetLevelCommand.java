package me.egg82.ssc.commands.internal;

import co.aikar.commands.CommandIssuer;
import co.aikar.taskchain.TaskChain;
import com.google.common.collect.ImmutableList;
import java.util.List;
import me.egg82.ssc.APIException;
import me.egg82.ssc.StaffChatAPI;
import me.egg82.ssc.core.LevelResult;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetLevelCommand implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final CommandIssuer issuer;
    private final String level;
    private final String name;
    private final TaskChain<?> chain;

    private final StaffChatAPI api = StaffChatAPI.getInstance();

    public SetLevelCommand(CommandIssuer issuer, String level, String name, TaskChain<?> chain) {
        this.issuer = issuer;
        this.level = level;
        this.name = name;
        this.chain = chain;
    }

    public void run() {
        if (level == null || level.isEmpty()) {
            issuer.sendError(Message.LEVEL__NO_LEVEL);
            return;
        }
        if (name == null || name.isEmpty()) {
            issuer.sendError(Message.LEVEL__NO_NAME);
            return;
        }

        issuer.sendInfo(Message.LEVEL__BEGIN);

        chain
                .<Boolean>asyncCallback((v, f) -> {
                    java.util.Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
                    if (!cachedConfig.isPresent()) {
                        logger.error("Cached config could not be fetched.");
                        f.accept(Boolean.FALSE);
                        return;
                    }

                    LevelResult l = getLevel(level, cachedConfig.get().getStorage());
                    if (l == null) {
                        issuer.sendError(Message.ERROR__LEVEL_NOT_FOUND);
                        f.accept(Boolean.TRUE);
                        return;
                    }

                    try {
                        api.setLevel(l.getLevel(), name);
                        issuer.sendInfo(Message.LEVEL__END);
                        f.accept(Boolean.TRUE);
                    } catch (APIException ex) {
                        logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                        f.accept(Boolean.FALSE);
                    }
                })
                .syncLast(f -> {
                    if (!f.booleanValue()) {
                        issuer.sendError(Message.ERROR__INTERNAL);
                    }
                })
                .execute();
    }

    private LevelResult getLevel(String l, List<Storage> storage) {
        if (l == null || l.isEmpty()) {
            return null;
        }

        ImmutableList<LevelResult> levels = null;
        for (Storage s : storage) {
            try {
                levels = s.getLevels();
                break;
            } catch (StorageException ex) {
                logger.error("Could not get levels from " + s.getClass().getSimpleName() + ".", ex);
            }
        }
        if (levels == null) {
            return null;
        }

        for (LevelResult level : levels) {
            if (String.valueOf(level.getLevel()).equalsIgnoreCase(l) || level.getName().toLowerCase().equalsIgnoreCase(l)) {
                return level;
            }
        }
        return isNumeric(l) ? new LevelResult(Byte.parseByte(l), null) : null;
    }

    private boolean isNumeric(String val) {
        try {
            if (Byte.parseByte(val) < 0) {
                return false;
            }
        } catch (NumberFormatException ignored) { return false; }
        return true;
    }
}
