package net.rinsuki.mcplugins.mc1218c;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;

public class MyPlugin extends JavaPlugin implements Listener {
    private long lastTime = -1;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    
    @EventHandler
    public void onTickStarted(ServerTickStartEvent event) {
        Server server = getServer();
        long currentTime = server.getWorld("world").getFullTime() % 24000;
        
        if (lastTime != -1 && currentTime < lastTime) {
            makeSnapshot(server.getConsoleSender());
        }
        lastTime = currentTime;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("snapshot")) {
            makeSnapshot(sender);
            return true;
        }
        return false;
    }
    
    void makeSnapshot(CommandSender sender) {
        Server server = getServer();
        long currentTick = server.getWorld("world").getGameTime();
        // save every world
        for (World world : server.getWorlds()) {
            world.save(true);
        }
        String name = "gt" + currentTick;
        
        try {
            // connect to @snapshot.sock
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

            String res = response.toString();
            
            if (res.length() > 0) {
                getLogger().info("Response: " + res);
            }
            if (res.startsWith("SUCCESS: ")) {
                sender.sendMessage("Snapshot created: " + res);
            } else {
                sender.sendMessage("Snapshot creation failed");
            }
        } catch (IOException e) {
            sender.sendMessage("Failed to create snapshot");
            getLogger().severe("Snapshot creation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
