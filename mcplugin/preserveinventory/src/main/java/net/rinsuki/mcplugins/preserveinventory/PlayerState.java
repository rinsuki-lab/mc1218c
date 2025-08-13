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
import org.bukkit.Material;

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

    // --- Refund handling ---
    public synchronized void saveRefund(String deathId, ItemStack paid) {
        if (paid == null || paid.getType() == Material.AIR || paid.getAmount() <= 0) return;
        YamlConfiguration yaml = load();
        String base = "refunds." + deathId;
        yaml.set(base + ".paid", paid);
        save(yaml);
    }

    public synchronized ItemStack takeRefund(String deathId) {
        YamlConfiguration yaml = load();
        String base = "refunds." + deathId;
        if (!yaml.contains(base)) return null;
        ItemStack paid = null;
        Object raw = yaml.get("refunds." + deathId + ".paid");
        if (raw instanceof ItemStack) {
            paid = (ItemStack) raw;
        }
        // Remove refund entry
        yaml.set(base, null);
        save(yaml);
        return paid;
    }

    public static class Cost {
        public final int iron;
        public final int diamond;
        public Cost(int iron, int diamond) {
            this.iron = iron;
            this.diamond = diamond;
        }
        public int total() { return iron + diamond; }
    }

    public static Cost computeCost(List<ItemStack> drops) {
        int iron = 0;
        int diamond = 0;
        if (drops != null) {
            for (ItemStack it : drops) {
                if (it == null) continue;
                Material m = it.getType();
                if (m == Material.AIR) continue;
                int amt = it.getAmount();
                if (isDiamondCostItem(m)) {
                    diamond += amt;
                } else if (isIronCostItem(m)) {
                    iron += amt;
                }
            }
        }
        if (diamond > 0) {
            iron = 0; // special rule: diamond overrides iron
        }
        return new Cost(iron, diamond);
    }

    public synchronized Cost getDeathCost(String deathId) {
        YamlConfiguration yaml = load();
        String base = "deaths." + deathId;
        if (!yaml.contains(base)) return null;
        List<ItemStack> drops = new ArrayList<>();
        List<?> raw = yaml.getList(base + ".drops");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack) {
                    drops.add((ItemStack) o);
                }
            }
        }
        return computeCost(drops);
    }

    private static boolean isIronCostItem(Material m) {
        switch (m) {
            case IRON_HELMET:
            case IRON_CHESTPLATE:
            case IRON_LEGGINGS:
            case IRON_BOOTS:
            case IRON_SWORD:
            case IRON_AXE:
            case IRON_PICKAXE:
            case IRON_SHOVEL:
            case IRON_HOE:
            case IRON_HORSE_ARMOR:
            case SHIELD:
                return true;
            default:
                return false;
        }
    }

    private static boolean isDiamondCostItem(Material m) {
        switch (m) {
            case DIAMOND_HELMET:
            case DIAMOND_CHESTPLATE:
            case DIAMOND_LEGGINGS:
            case DIAMOND_BOOTS:
            case DIAMOND_SWORD:
            case DIAMOND_AXE:
            case DIAMOND_PICKAXE:
            case DIAMOND_SHOVEL:
            case DIAMOND_HOE:
            case DIAMOND_HORSE_ARMOR:
            case NETHERITE_HELMET:
            case NETHERITE_CHESTPLATE:
            case NETHERITE_LEGGINGS:
            case NETHERITE_BOOTS:
            case NETHERITE_SWORD:
            case NETHERITE_AXE:
            case NETHERITE_PICKAXE:
            case NETHERITE_SHOVEL:
            case NETHERITE_HOE:
            case ELYTRA:
            case SHULKER_BOX:
                return true;
            default:
                break;
        }
        // All colored shulker box variants
        String name = m.name();
        return name.endsWith("_SHULKER_BOX");
    }
}
