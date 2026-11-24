package me.zeppy.maceControl;

import de.tr7zw.changeme.nbtapi.NBTCompoundList;
import de.tr7zw.changeme.nbtapi.NBTFile;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MaceTracker {
    private final MaceControl plugin;
    private final AtomicInteger maceCount = new AtomicInteger(0);

    private final ConcurrentHashMap<String, UUID> maceIdToHolder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> currentMaceHolders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MaceDropInfo> pendingDrops = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static class MaceDropInfo {
        public final UUID originalHolder;
        public final String maceId;
        public final long dropTime;

        public MaceDropInfo(UUID originalHolder, String maceId) {
            this.originalHolder = originalHolder;
            this.maceId = maceId;
            this.dropTime = System.currentTimeMillis();
        }
    }

    public int getMaceCount() {
        return maceCount.get();
    }

    public void setMaceCount(int count) {
        maceCount.set(Math.max(0, count));
    }

    public Map<String, UUID> getMaceIdToHolder() {
        return maceIdToHolder;
    }

    public Map<UUID, Integer> getCurrentMaceHolders() {
        return currentMaceHolders;
    }

    public Map<UUID, UUID> getPendingDrops() {
        Map<UUID, UUID> oldFormat = new HashMap<>();
        for (Map.Entry<UUID, MaceDropInfo> entry : pendingDrops.entrySet()) {
            oldFormat.put(entry.getKey(), entry.getValue().originalHolder);
        }
        return oldFormat;
    }

    public Map<UUID, MaceDropInfo> getPendingDropsNew() {
        return pendingDrops;
    }

    public MaceControl getPlugin() {
        return plugin;
    }

    public MaceTracker(MaceControl plugin) {
        this.plugin = plugin;
    }

    void refreshMaceTracking() {
        lock.writeLock().lock();
        try {
            plugin.getLogger().info("Starting mace tracking refresh (online + offline)...");

            maceCount.set(0);
            currentMaceHolders.clear();
            Set<String> foundMaceIds = new HashSet<>();
            Set<UUID> validDrops = new HashSet<>();

            // Step 1: Scan online players
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                int playerMaceCount = scanPlayerInventory(player, foundMaceIds);
                if (playerMaceCount > 0) {
                    currentMaceHolders.put(player.getUniqueId(), playerMaceCount);
                    maceCount.addAndGet(playerMaceCount);
                }
            }

            // Step 2: Scan offline players (.dat files)
            int offlineMaces = scanOfflinePlayers(foundMaceIds);
            maceCount.addAndGet(offlineMaces);

            // Step 3: Scan for dropped maces in loaded chunks
            int droppedMaceCount = scanDroppedMaces(foundMaceIds, validDrops);
            maceCount.addAndGet(droppedMaceCount);

            // Step 4: Clean up stale mappings
            cleanupStaleMappings(foundMaceIds, validDrops);

            plugin.getLogger().info("Mace tracking refresh complete. Total maces: " + maceCount.get() +
                    ", Active holders (online+offline): " + currentMaceHolders.size() +
                    ", Dropped maces: " + validDrops.size());

        } catch (Exception e) {
            plugin.getLogger().severe("Error during mace tracking refresh: " + e.getMessage());
            e.printStackTrace();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int scanOfflinePlayers(Set<String> foundMaceIds) {
        int totalOfflineMaces = 0;
        if (Bukkit.getWorlds().isEmpty()) return 0;

        File playerDataFolder = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
        if (!playerDataFolder.exists() || !playerDataFolder.isDirectory()) {
            return 0;
        }

        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.isOnline()) continue;

            UUID uuid = offlinePlayer.getUniqueId();
            File playerFile = new File(playerDataFolder, uuid + ".dat");
            if (!playerFile.exists()) continue;

            try {
                NBTFile nbtFile = new NBTFile(playerFile);
                int playerOfflineMaces = 0;

                playerOfflineMaces += countMacesInNbtList(nbtFile.getCompoundList("Inventory"), uuid, foundMaceIds);
                playerOfflineMaces += countMacesInNbtList(nbtFile.getCompoundList("EnderItems"), uuid, foundMaceIds);

                if (playerOfflineMaces > 0) {
                    currentMaceHolders.put(uuid, playerOfflineMaces);
                    totalOfflineMaces += playerOfflineMaces;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read offline data for " + uuid + ": " + e.getMessage());
            }
        }
        return totalOfflineMaces;
    }

    private int countMacesInNbtList(NBTCompoundList items, UUID ownerUuid, Set<String> foundMaceIds) {
        if (items == null) return 0;
        int count = 0;

        for (ReadWriteNBT item : items) {
            String id = item.getString("id");
            if ("minecraft:mace".equalsIgnoreCase(id)) {
                int stackCount = item.hasTag("count") ? item.getInteger("count") :
                                 (item.hasTag("Count") ? item.getByte("Count") : 1);
                count += Math.max(1, stackCount);

                // Extract custom mace_id from 1.20.5+ components if present
                ReadWriteNBT components = item.getCompound("components");
                if (components != null) {
                    ReadWriteNBT customData = components.getCompound("minecraft:custom_data");
                    if (customData != null) {
                        ReadWriteNBT bukkitValues = customData.getCompound("PublicBukkitValues");
                        if (bukkitValues != null && bukkitValues.hasTag("macecontrol:mace_id")) {
                            String maceId = bukkitValues.getString("macecontrol:mace_id");
                            if (maceId != null && !maceId.isEmpty()) {
                                foundMaceIds.add(maceId);
                                maceIdToHolder.put(maceId, ownerUuid);
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    private int scanPlayerInventory(Player player, Set<String> foundMaceIds) {
        int playerMaceCount = 0;

        try {
            ItemStack[] contents = player.getInventory().getContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && MaceControl.getManager().isUniqueMace(item)) {
                        playerMaceCount++;
                        String maceId = MaceControl.getManager().getMaceID(item);
                        if (maceId != null) {
                            foundMaceIds.add(maceId);
                            maceIdToHolder.put(maceId, player.getUniqueId());
                        }
                    }
                }
            }

            // Also scan online player's ender chest
            ItemStack[] enderContents = player.getEnderChest().getContents();
            if (enderContents != null) {
                for (ItemStack item : enderContents) {
                    if (item != null && MaceControl.getManager().isUniqueMace(item)) {
                        playerMaceCount++;
                        String maceId = MaceControl.getManager().getMaceID(item);
                        if (maceId != null) {
                            foundMaceIds.add(maceId);
                            maceIdToHolder.put(maceId, player.getUniqueId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error scanning inventory for player " + player.getName() + ": " + e.getMessage());
        }

        return playerMaceCount;
    }

    private int scanDroppedMaces(Set<String> foundMaceIds, Set<UUID> validDrops) {
        int droppedMaceCount = 0;

        try {
            int maxWorldsToScan = plugin.getConfig().getInt("performance.max-worlds-to-scan", 3);
            int worldsScanned = 0;

            for (World world : plugin.getServer().getWorlds()) {
                if (worldsScanned >= maxWorldsToScan) break;
                worldsScanned++;

                Chunk[] loadedChunks = world.getLoadedChunks();
                int maxChunksPerWorld = plugin.getConfig().getInt("performance.max-chunks-per-world", 100);
                int chunksToScan = Math.min(loadedChunks.length, maxChunksPerWorld);

                for (int i = 0; i < chunksToScan; i++) {
                    Chunk chunk = loadedChunks[i];
                    try {
                        for (Entity entity : chunk.getEntities()) {
                            if (entity instanceof Item droppedItem) {
                                ItemStack itemStack = droppedItem.getItemStack();
                                if (itemStack != null && MaceControl.getManager().isUniqueMace(itemStack)) {
                                    droppedMaceCount++;
