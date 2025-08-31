#!/bin/bash
ln -s /minecraft/{rw/,}banned-ips.json
ln -s /minecraft/{rw/,}banned-players.json
ln -s /minecraft/{rw/,}version_history.json
ln -s /minecraft/{rw/,}whitelist.json
ln -s /minecraft/{rw/,}worlds
ln -s /minecraft/{rot/,}plugins/TabTPS
ln -s /minecraft/{rot/,}plugins/UnifiedMetrics
ln -s /minecraft/{rot/,}plugins/DiscordSRV
ln -s /minecraft/{rot/,}plugins/BlueMap
mkdir /minecraft/plugins/PreserveInventory
ln -s /minecraft/{rw/,}plugins/PreserveInventory/players
mkdir /minecraft/plugins/Sign2Marker
ln -s /minecraft/{rw/,}plugins/Sign2Marker/markers
ln -s /minecraft/{rot/,}config
ln -s /minecraft/{ro/,}bukkit.yml
ln -s /minecraft/{ro/,}commands.yml
ln -s /minecraft/{ro/,}help.yml
ln -s /minecraft/{ro/,}ops.json
ln -s /minecraft/{ro/,}permissions.yml
ln -s /minecraft/{ro/,}server.properties
ln -s /minecraft/{ro/,}spigot.yml
ln -s /minecraft/{rwcache/,}bluemap