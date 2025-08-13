package net.rinsuki.mcplugins.preserveinventory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

public class PlayerState {
    private final UUID uuid;
    private final File file;
    private final Logger logger;

    public PlayerState(UUID uuid, File dir, Logger logger) {
        this.uuid = uuid;
        this.file = new File(dir, uuid.toString() + ".yml");
        this.logger = logger;
    }

    public synchronized boolean isEnabled() {
        // default ON (opt-out)
        if (!file.exists()) {
            return true;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return yaml.getBoolean("enabled", true);
    }

    public synchronized void setEnabled(boolean enabled) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", enabled);
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("Failed to save state for " + uuid + ": " + e.getMessage());
        }
    }
}
