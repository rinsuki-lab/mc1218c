#!/bin/bash
ln -s /minecraft/{rw/,}banned-ips.json
ln -s /minecraft/{rw/,}banned-players.json
ln -s /minecraft/{rw/,}version_history.json
ln -s /minecraft/{rw/,}whitelist.json
ln -s /minecraft/{rw/,}worlds
ln -s /minecraft/{rot/,}plugins/TabTPS
ln -s /minecraft/{rot/,}plugins/UnifiedMetrics
ln -s /minecraft/{rot/,}plugins/DiscordSRV
mkdir /minecraft/plugins/PreserveInventory
ln -s /minecraft/{rw/,}plugins/PreserveInventory/players
ln -s /minecraft/{rot/,}config
ln -s /minecraft/{ro/,}bukkit.yml
ln -s /minecraft/{ro/,}commands.yml
ln -s /minecraft/{ro/,}help.yml
ln -s /minecraft/{ro/,}ops.json
ln -s /minecraft/{ro/,}permissions.yml
ln -s /minecraft/{ro/,}server.properties
ln -s /minecraft/{ro/,}spigot.yml
