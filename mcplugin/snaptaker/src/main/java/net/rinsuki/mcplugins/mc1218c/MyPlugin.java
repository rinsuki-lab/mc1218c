package net.rinsuki.mcplugins.mc1218c;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.SleepStatus;

public class MyPlugin extends JavaPlugin implements Listener {
    private long lastTime = -1;
    private volatile long lastPlayerQuitAtMillis = -1;
    private long lastWarnDay = -1; // day index for which we sent the 1-minute warning
    private Optional<Field> sleepStatusField = Arrays.stream(ServerLevel.class.getDeclaredFields())
        .filter(f -> f.getType() == SleepStatus.class)
        .findFirst();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Record the time when a player quits
        lastPlayerQuitAtMillis = System.currentTimeMillis();
    }


    final long TICK_PER_DAY = 24000;
    final long TICK_PER_SECOND = 20;

    @EventHandler
    public void onTickStarted(ServerTickStartEvent event) {
        Server server = getServer();
        long fullTime = server.getWorld("world").getFullTime();
        long currentTime = fullTime % 24000;
        long currentDay = fullTime / 24000;
        
        if (lastTime != -1 && lastTime < (TICK_PER_DAY - 100) && currentTime >= (TICK_PER_DAY - 100) && lastWarnDay != currentDay) {
            // announce upcoming backup
            getServer().broadcast(Component.text("バックアップがもうすぐ行われます"));
            lastWarnDay = currentDay;
        }

        if (lastTime != -1 && currentTime < lastTime) {
            makeSnapshot(server.getConsoleSender());
        }
        lastTime = currentTime;
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        World world = event.getPlayer().getWorld();
        CraftWorld craftWorld = (CraftWorld) world;
        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
            try {
                Field sleepStatusField = this.sleepStatusField.get();
                sleepStatusField.setAccessible(true);
                SleepStatus sleepStatus = (SleepStatus) sleepStatusField.get(craftWorld.getHandle());
                if (sleepStatus.areEnoughSleeping(world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE))) {
                    getServer().broadcast(Component.text("バックアップがもうすぐ行われます"));
                }
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                return;
            } catch (IllegalAccessException e) {
                e.printStackTrace();
                return;
            }
        });
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("snapshot")) {
            makeSnapshot(sender);
            return true;
        } else if (command.getName().equalsIgnoreCase("pos")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                int x = player.getLocation().getBlockX();
                int y = player.getLocation().getBlockY();
                int z = player.getLocation().getBlockZ();
                String worldName = player.getWorld().getName();
                String message = String.format("I'm at %s (x: %d, y: %d, z: %d)", worldName, x, y, z);
                // Make the player say the coordinates in chat
                player.chat(message);
            } else {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
            }
            return true;
        }
        return false;
    }
    
    void makeSnapshot(CommandSender sender) {
        Server server = getServer();
        long currentTick = server.getWorld("world").getGameTime();
        // save every world and measure time
        long saveStartNs = System.nanoTime();
        for (World world : server.getWorlds()) {
            world.save(true);
        }
        long saveElapsedMs = (System.nanoTime() - saveStartNs) / 1_000_000L;
        String name = "gt" + currentTick;
        
        try {
            // connect to @snapshot.sock
            long snapshotStartNs = System.nanoTime();
            SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
            socket.connect(UnixDomainSocketAddress.of("/run/snapshotter/snapshot.sock"));
            
            // send the name to the socket
            ByteBuffer nameBuffer = ByteBuffer.wrap(name.getBytes(StandardCharsets.UTF_8));
            while (nameBuffer.hasRemaining()) {
                socket.write(nameBuffer);
            }
            
            // close send (shutdown output)
            socket.shutdownOutput();
            
            // read until eof
            ByteBuffer readBuffer = ByteBuffer.allocate(1024);
            StringBuilder response = new StringBuilder();
            while (socket.read(readBuffer) != -1) {
                readBuffer.flip();
                response.append(StandardCharsets.UTF_8.decode(readBuffer));
                readBuffer.clear();
            }
            
            // close socket
            socket.close();
            long snapshotElapsedMs = (System.nanoTime() - snapshotStartNs) / 1_000_000L;

            String res = response.toString();
            
            if (res.length() > 0) {
                getLogger().info("Response: " + res);
            }
            if (res.startsWith("SUCCESS: ")) {
                sender.sendMessage("Snapshot created: " + res + String.format(" (save: %dms, snapshot: %dms)", saveElapsedMs, snapshotElapsedMs));
                // Broadcast to all players after snapshot creation
                getServer().broadcast(Component.text(String.format("バックアップを作成しました: %s (保存: %dms, スナップショット: %dms)", name, saveElapsedMs, snapshotElapsedMs)));
                // If someone left within the last minute, notify Discord via console
                long now = System.currentTimeMillis();
                if (lastPlayerQuitAtMillis > 0 && (now - lastPlayerQuitAtMillis) <= 60_000L) {
                    Bukkit.dispatchCommand(server.getConsoleSender(), "discord broadcast 朝が来ました");
                    // Reset to avoid duplicate notifications for the same quit
                    lastPlayerQuitAtMillis = -1;
                }
            } else {
                sender.sendMessage("Snapshot creation failed");
            }
        } catch (IOException e) {
            sender.sendMessage("Failed to create snapshot");
            getLogger().severe("Snapshot creation failed: " + e.getMessage());
            e.printStackTrace();
            getServer().broadcast(Component.text("バックアップの作成に失敗しました"));
        }
    }
}
