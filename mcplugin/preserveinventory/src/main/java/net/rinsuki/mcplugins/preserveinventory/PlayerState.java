package net.rinsuki.mcplugins.preserveinventory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.ArrayList;
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

    public static class DeathSummary {
        public final String deathId;
        public final String createdAt;
        public final String world;
        public final double x;
        public final double y;
        public final double z;

        public final List<ItemStack> drops;

        public DeathSummary(String deathId, String createdAt, String world, double x, double y, double z, List<ItemStack> drops) {
            this.deathId = deathId;
            this.createdAt = createdAt;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.drops = drops;
        }
    }

    public synchronized List<DeathSummary> listDeaths() {
        YamlConfiguration yaml = load();
        ConfigurationSection sec = yaml.getConfigurationSection("deaths");
        List<DeathSummary> list = new ArrayList<>();
        if (sec == null) {
            return list;
        }
        for (String id : sec.getKeys(false)) {
            String base = "deaths." + id;
            String createdAt = yaml.getString(base + ".createdAt", "");
            String world = yaml.getString(base + ".location.world", "unknown");
            double x = yaml.getDouble(base + ".location.x");
            double y = yaml.getDouble(base + ".location.y");
            double z = yaml.getDouble(base + ".location.z");
            List<ItemStack> drops = new ArrayList<>();
            List<?> raw = yaml.getList(base + ".drops");
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof ItemStack) {
                        drops.add((ItemStack) o);
                    }
                }
            }
            list.add(new DeathSummary(id, createdAt, world, x, y, z, drops));
        }
        return list;
    }

    public synchronized List<String> listDeathIds() {
        YamlConfiguration yaml = load();
        ConfigurationSection sec = yaml.getConfigurationSection("deaths");
        if (sec == null) {
            return List.of();
        }
        return new ArrayList<>(sec.getKeys(false));
    }

    public synchronized List<ItemStack> takeAndRemove(String deathId) {
        YamlConfiguration yaml = load();
        String base = "deaths." + deathId;
        if (!yaml.contains(base)) {
            return null;
        }
        List<ItemStack> drops = new ArrayList<>();
        List<?> raw = yaml.getList(base + ".drops");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack) {
                    drops.add((ItemStack) o);
                }
            }
        }
        // Remove the entry
        yaml.set(base, null);
        save(yaml);
        return drops;
    }
}
