package net.rinsuki.mcplugins.sign2marker;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import com.flowpowered.math.vector.Vector3d;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MyPlugin extends JavaPlugin implements Listener {

    private static final String MARKER_SET_ID = "Sign2Marker"; // README 指定に合わせる
    private static final String MARKER_SET_LABEL = "Sign2Marker";

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("Asia/Tokyo"));

    @Override
    public void onEnable() {
        // Ensure data folders exist
        getDataFolder().mkdirs();
        File markersRoot = new File(getDataFolder(), "markers");
        if (!markersRoot.exists()) markersRoot.mkdirs();

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(this, this);

        // When BlueMap becomes available, (re)load all stored markers
        BlueMapAPI.onEnable(api -> {
            getLogger().info("BlueMap detected; loading saved markers...");
            loadAllStoredMarkersToBlueMap(api);
        });
    }

    // -------------------- Event Handling --------------------

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        // Only react to FRONT side as per spec (表側)
        if (event.getSide() != org.bukkit.block.sign.Side.FRONT) return;

        Location loc = event.getBlock().getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(event.line(i))); // Paper/Adventure API
        }

        boolean isPoi = isPoiFirstLine(lines.get(0));
        boolean existed = hasStoredMarkerAt(loc);

        if (isPoi) {
            List<String> desc = extractDescription(lines);
            Player player = event.getPlayer();
            String playerName = player != null ? player.getName() : "unknown";
            UUID playerUuid = player != null ? player.getUniqueId() : new UUID(0, 0);
            upsertMarker(loc, desc, playerName, playerUuid, Instant.now());
            broadcast(String.format("Sign2Marker: %s が %s (%d, %d, %d) の看板からマーカーを%sしました。",
                    playerName,
                    world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    existed ? "更新" : "作成"
            ));
        } else {
            if (existed) {
                Player player = event.getPlayer();
                String playerName = player != null ? player.getName() : "unknown";
                removeMarker(loc);
                broadcast(String.format("Sign2Marker: %s が %s (%d, %d, %d) のマーカーを削除しました。",
                        playerName,
                        world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()
                ));
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) return;
        Location loc = event.getBlock().getLocation();
        if (!hasStoredMarkerAt(loc)) return;
        removeMarker(loc);
        broadcast(String.format("Sign2Marker: %s が %s (%d, %d, %d) の看板を壊したため、マーカーを削除しました。",
                event.getPlayer().getName(),
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()
        ));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplodedBlocks(event.blockList(), "爆発");
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplodedBlocks(event.blockList(), "爆発");
    }

    private void handleExplodedBlocks(List<org.bukkit.block.Block> blocks, String reason) {
        for (org.bukkit.block.Block b : blocks) {
            if (!(b.getState() instanceof Sign)) continue;
            Location loc = b.getLocation();
            if (!hasStoredMarkerAt(loc)) continue;
            removeMarker(loc);
            broadcast(String.format("Sign2Marker: %s (%d, %d, %d) の看板が%sで壊れたため、マーカーを削除しました。",
                    loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    reason
            ));
        }
    }

    // -------------------- Storage --------------------

    private File getChunkFile(Location loc) {
        World world = Objects.requireNonNull(loc.getWorld());
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        File worldDir = new File(new File(getDataFolder(), "markers"), world.getName());
        if (!worldDir.exists()) worldDir.mkdirs();
        return new File(worldDir, String.format("chunk.%d.%d.yaml", cx, cz));
    }

    private String coordKey(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private boolean hasStoredMarkerAt(Location loc) {
        File f = getChunkFile(loc);
        if (!f.exists()) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        return yml.contains("markers." + coordKey(loc));
    }

    private void saveMarkerToYaml(Location loc, List<String> descriptionLines, String playerName, UUID playerUuid) {
        File f = getChunkFile(loc);
        YamlConfiguration yml = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

        String base = "markers." + coordKey(loc);
        yml.set(base + ".x", loc.getBlockX());
        yml.set(base + ".y", loc.getBlockY());
        yml.set(base + ".z", loc.getBlockZ());
        yml.set(base + ".text", descriptionLines);
        yml.set(base + ".lastEditor.name", playerName);
        yml.set(base + ".lastEditor.uuid", playerUuid.toString());
        try {
            yml.save(f);
        } catch (IOException e) {
            getLogger().warning("Failed to save marker YAML: " + e.getMessage());
        }
    }

    private void removeMarkerFromYaml(Location loc) {
        File f = getChunkFile(loc);
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String base = "markers." + coordKey(loc);
        if (!yml.contains(base)) return;
        yml.set(base, null);
        try {
            yml.save(f);
        } catch (IOException e) {
            getLogger().warning("Failed to update marker YAML: " + e.getMessage());
        }
    }

    // -------------------- BlueMap integration --------------------

    private void upsertMarker(Location loc, List<String> descriptionLines, String playerName, UUID playerUuid, Instant now) {
        saveMarkerToYaml(loc, descriptionLines, playerName, playerUuid);

        BlueMapAPI.getInstance().ifPresent(api -> applyToWorldMaps(api, loc.getWorld(), map -> {
            MarkerSet set = map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, k -> new MarkerSet(MARKER_SET_LABEL));
            String id = coordKey(loc);
            String label = firstNonEmpty(descriptionLines).orElse("POI");
            String detail = buildDetailHtml(descriptionLines, playerName, playerUuid, loc, now);
            Marker existing = set.getMarkers().get(id);
            if (existing instanceof POIMarker poi) {
                poi.setLabel(label);
                poi.setPosition(new Vector3d(loc.getX() + 0.5, loc.getY() + 0.5, loc.getZ() + 0.5));
                poi.setDetail(detail);
            } else {
                POIMarker poi = new POIMarker(label, new Vector3d(loc.getX() + 0.5, loc.getY() + 0.5, loc.getZ() + 0.5));
                poi.setDetail(detail);
                set.getMarkers().put(id, poi);
            }
        }));
    }

    private void removeMarker(Location loc) {
        removeMarkerFromYaml(loc);

        BlueMapAPI.getInstance().ifPresent(api -> applyToWorldMaps(api, loc.getWorld(), map -> {
            MarkerSet set = map.getMarkerSets().get(MARKER_SET_ID);
            if (set != null) set.getMarkers().remove(coordKey(loc));
        }));
    }

    private void applyToWorldMaps(BlueMapAPI api, World world, java.util.function.Consumer<BlueMapMap> consumer) {
        if (world == null) return;
        Optional<BlueMapWorld> bmWorld = api.getWorld(world);
        if (bmWorld.isEmpty()) return;
        for (BlueMapMap map : bmWorld.get().getMaps()) consumer.accept(map);
    }

    private void loadAllStoredMarkersToBlueMap(BlueMapAPI api) {
        File markersRoot = new File(getDataFolder(), "markers");
        if (!markersRoot.exists()) return;

        File[] worldDirs = markersRoot.listFiles(File::isDirectory);
        if (worldDirs == null) return;

        for (File worldDir : worldDirs) {
            World world = Bukkit.getWorld(worldDir.getName());
            if (world == null) continue; // skip unloaded worlds

            File[] chunkFiles = worldDir.listFiles((dir, name) -> name.startsWith("chunk.") && name.endsWith(".yaml"));
            if (chunkFiles == null) continue;

            for (File cf : chunkFiles) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(cf);
                if (!yml.isConfigurationSection("markers")) continue;
                for (String key : Objects.requireNonNull(yml.getConfigurationSection("markers")).getKeys(false)) {
                    String base = "markers." + key;
                    int x = yml.getInt(base + ".x");
                    int y = yml.getInt(base + ".y");
                    int z = yml.getInt(base + ".z");
                    List<String> desc = yml.getStringList(base + ".text");
                    String name = yml.getString(base + ".lastEditor.name", "unknown");
                    String uuidStr = yml.getString(base + ".lastEditor.uuid", new UUID(0, 0).toString());
                    UUID uuid;
                    try { uuid = UUID.fromString(uuidStr); } catch (Exception e) { uuid = new UUID(0, 0); }

                    Location loc = new Location(world, x, y, z);
                    // Use file's lastModified as a best-effort timestamp for reloads
                    Instant ts = Instant.ofEpochMilli(cf.lastModified());
                    upsertMarker(loc, desc, name, uuid, ts);
                }
            }
        }
    }

    // -------------------- Utils --------------------

    private boolean isPoiFirstLine(String line) {
        return "[poi]".equalsIgnoreCase(line == null ? null : line.trim());
    }

    private List<String> extractDescription(List<String> lines) {
        List<String> desc = new ArrayList<>();
        for (int i = 1; i < Math.min(4, lines.size()); i++) {
            String s = lines.get(i);
            if (s == null) s = "";
            desc.add(s);
        }
        // Trim trailing empty lines
        for (int i = desc.size() - 1; i >= 0; i--) {
            if (desc.get(i).isEmpty()) desc.remove(i); else break;
        }
        return desc;
    }

    private Optional<String> firstNonEmpty(List<String> list) {
        for (String s : list) if (s != null && !s.isEmpty()) return Optional.of(s);
        return Optional.empty();
    }

    private String buildDetailHtml(List<String> descriptionLines, String playerName, UUID playerUuid, Location loc, Instant ts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div>");
        // 1) 看板の2行目以降の内容
        sb.append("<div>");
        if (!descriptionLines.isEmpty()) {
            for (int i = 0; i < descriptionLines.size(); i++) {
                if (i > 0) sb.append("<br>");
                sb.append(htmlEscape(descriptionLines.get(i)));
            }
        } else {
            sb.append("(説明なし)");
        }
        sb.append("</div>");

        // 2) マーカーがある座標
        sb.append("<div style=\"margin-top:4px;color:#888\">");
        sb.append("座標: ").append(loc.getBlockX()).append(", ").append(loc.getBlockY()).append(", ").append(loc.getBlockZ());
        sb.append("</div>");

        // 3) 最終更新者のプレーヤー名と現実時間 (JST)
        sb.append("<div style=\"margin-top:2px;color:#888\">");
        sb.append("最終更新: ").append(htmlEscape(playerName)).append(" (").append(playerUuid).append(") ");
        sb.append(htmlEscape(timeFormatter.format(ts))).append(" (JST)");
        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void broadcast(String msg) {
        Bukkit.getServer().broadcastMessage(msg);
    }
}
