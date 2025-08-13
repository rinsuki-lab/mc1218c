package net.rinsuki.mcplugins.preserveinventory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import java.util.List;
import java.time.Instant;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

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
        YamlConfiguration yaml = load();
        yaml.set("enabled", enabled);
        save(yaml);
    }

    private YamlConfiguration load() {
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        return new YamlConfiguration();
    }

    private void save(YamlConfiguration yaml) {
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("Failed to save state for " + uuid + ": " + e.getMessage());
        }
    }

    public synchronized void saveDeathSnapshot(String deathId, Location loc, List<ItemStack> drops) {
        YamlConfiguration yaml = load();
        String base = "deaths." + deathId;
        yaml.set(base + ".createdAt", Instant.now().toString());

        String worldName = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
        yaml.set(base + ".location.world", worldName);
        yaml.set(base + ".location.x", loc.getX());
        yaml.set(base + ".location.y", loc.getY());
        yaml.set(base + ".location.z", loc.getZ());

        // Save drops as-is; Bukkit serializes ItemStack with full NBT/ItemMeta
        yaml.set(base + ".drops", drops);

        save(yaml);
    }
}
