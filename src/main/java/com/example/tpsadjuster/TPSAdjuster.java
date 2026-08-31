// TPSAdjuster.java
package com.example.tpsadjuster;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class TPSAdjuster extends JavaPlugin implements Listener, org.bukkit.command.CommandExecutor {
    private Map<String, Object> originalValues = new HashMap<>();
    private Map<String, Object> currentValues = new HashMap<>();
    private Map<String, Number> minValues = new HashMap<>();
    private Map<String, Number> maxValues = new HashMap<>();
    private Map<String, Number> stepSizes = new HashMap<>();
    private Map<String, Boolean> enabledSettings = new HashMap<>();
    private Map<String, String> nerfDirections = new HashMap<>();

    private FileConfiguration config;
    private BukkitTask monitorTask;
    private double villagerAINerfThreshold = 15.0;
    private double villagerAIUnnerfThreshold = 18.5;
    private boolean villagersNerfed = false;
    private LinkedList<Double> tpsHistory = new LinkedList<>();
    private int tpsAverageWindow = 5;
    private long lastAdjustmentTime = 0;
    private long adjustmentCooldownTicks = 6000;
    private double tpsNerfThreshold = 17.0;
    private double tpsUnnerfThreshold = 19.5;
    private boolean debugLogging = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        loadValuesFromConfig();
        currentValues.putAll(originalValues);

        tpsAverageWindow = config.getInt("tps-average-window", 5);
        adjustmentCooldownTicks = config.getLong("adjustment-cooldown-ticks", 6000L);
        tpsNerfThreshold = config.getDouble("tps-nerf-threshold", 17.0);
        tpsUnnerfThreshold = config.getDouble("tps-unnerf-threshold", 19.5);
        villagerAINerfThreshold = config.getDouble("villager-ai-nerf-threshold", 15.0);
        villagerAIUnnerfThreshold = config.getDouble("villager-ai-unnerf-threshold", 18.5);
        debugLogging = config.getBoolean("debug-logging", false);

        if (villagerAIUnnerfThreshold <= villagerAINerfThreshold) {
            getLogger().warning("villager-ai-unnerf-threshold must be higher than villager-ai-nerf-threshold. Adjusting.");
            villagerAIUnnerfThreshold = villagerAINerfThreshold + 2.5;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        this.getCommand("tpsadjust").setExecutor(this);

        new EntityDespawnEnforcer().runTaskTimer(this, 0L, 100L);
        new ItemMergerTask().runTaskTimer(this, 0L, 200L);

        startMonitorTask();
        getLogger().info("TPSAdjuster enabled. Loaded " + originalValues.size() + " settings.");
    }

    @Override
    public void onDisable() {
        if (monitorTask != null) monitorTask.cancel();
        if (villagersNerfed) toggleVillagerAI(true);
        getLogger().info("TPSAdjuster disabled.");
    }

    private void startMonitorTask() {
        long monitorInterval = config.getLong("monitor-interval", 1200L);
        monitorTask = new TPSMonitorTask().runTaskTimer(this, 0L, monitorInterval);
    }

    private void loadValuesFromConfig() {
        originalValues.clear();
        minValues.clear();
        maxValues.clear();
        stepSizes.clear();
        enabledSettings.clear();
        nerfDirections.clear();
        ConfigurationSection settingsSection = config.getConfigurationSection("settings");
        if (settingsSection != null) {
            for (String setting : settingsSection.getKeys(false)) {
                ConfigurationSection sec = settingsSection.getConfigurationSection(setting);
                if (sec == null) continue;
                Object orig = sec.get("original");
                if (orig == null) continue;
                originalValues.put(setting, orig);
                if (orig instanceof Number) {
                    Number min = getNumber(sec, "min");
                    Number max = getNumber(sec, "max");
                    Number step = getNumber(sec, "step");
                    if (min == null || max == null || step == null) {
                        originalValues.remove(setting);
                        continue;
                    }
                    minValues.put(setting, min);
                    maxValues.put(setting, max);
                    stepSizes.put(setting, step);
                }
                enabledSettings.put(setting, sec.getBoolean("enabled", true));
                nerfDirections.put(setting, sec.getString("nerf_direction", "decrease"));
            }
        } else {
            fallbackToHardcoded();
        }
        currentValues.putAll(originalValues);
    }

    private Number getNumber(ConfigurationSection sec, String key) {
        if (sec.isInt(key)) return sec.getInt(key);
        if (sec.isDouble(key)) return sec.getDouble(key);
        return null;
    }

    private void fallbackToHardcoded() {
        putHardcodedNumberSetting("view-distance", 16, 4, 16, 1, true, "decrease");
        putHardcodedNumberSetting("simulation-distance", 10, 4, 10, 1, true, "decrease");
        putHardcodedNumberSetting("mob-spawn-range", 8, 4, 8, 1, true, "decrease");
        putHardcodedNumberSetting("despawn-ranges-monster-hard", 128, 64, 128, 16, true, "decrease");
        putHardcodedNumberSetting("despawn-ranges-monster-soft", 32, 16, 32, 4, true, "decrease");
        putHardcodedNumberSetting("global-monster-cap", 1400, 400, 2000, 100, true, "decrease");
        putHardcodedBooleanSetting("nerf-spawner-mobs", false, false);
    }

    private void putHardcodedNumberSetting(String setting, Number original, Number min, Number max, Number step, boolean enabled, String nerfDirection) {
        originalValues.put(setting, original);
        minValues.put(setting, min);
        maxValues.put(setting, max);
        stepSizes.put(setting, step);
        enabledSettings.put(setting, enabled);
        nerfDirections.put(setting, nerfDirection);
    }

    private void putHardcodedBooleanSetting(String setting, Boolean original, boolean enabled) {
        originalValues.put(setting, original);
        enabledSettings.put(setting, enabled);
        nerfDirections.put(setting, "increase");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tpsadjust")) return false;

        if (args.length == 0) {
            sender.sendMessage("Current TPS adjustments:");
            for (String setting : currentValues.keySet()) {
                Object current = currentValues.get(setting);
                Object original = originalValues.get(setting);
                sender.sendMessage(setting + ": " + current + " (original: " + original + ")");
            }
            sender.sendMessage("Villagers nerfed: " + villagersNerfed);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            config = getConfig();
            loadValuesFromConfig();
            currentValues.putAll(originalValues);
            sender.sendMessage("TPSAdjuster config reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            for (String setting : originalValues.keySet()) {
                applyAdjustment(setting, originalValues.get(setting));
            }
            if (villagersNerfed) toggleVillagerAI(true);
            villagersNerfed = false;
            sender.sendMessage("All settings reset to original values.");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            double avgTPS = getAverageTPS();
            sender.sendMessage("Average TPS: " + String.format("%.2f", avgTPS));
            sender.sendMessage("Villagers nerfed: " + villagersNerfed);
            sender.sendMessage("Nerf threshold: " + villagerAINerfThreshold);
            sender.sendMessage("Unnerf threshold: " + villagerAIUnnerfThreshold);
            return true;
        }

        if (args[0].equalsIgnoreCase("villager")) {
            if (args.length < 3 || !args[1].equalsIgnoreCase("ai")) {
                sender.sendMessage("Usage: /tpsadjust villager ai <enable|disable>");
                return true;
            }
            if (args[2].equalsIgnoreCase("enable")) {
                toggleVillagerAI(true);
                villagersNerfed = false;
                sender.sendMessage("Villager AI manually enabled.");
                return true;
            } else if (args[2].equalsIgnoreCase("disable")) {
                toggleVillagerAI(false);
                villagersNerfed = true;
                sender.sendMessage("Villager AI manually disabled.");
                return true;
            }
            return true;
        }

        return false;
    }

    private void applyAdjustment(String setting, Object value) {
        currentValues.put(setting, value);
        switch (setting) {
            case "view-distance":
                for (World world : Bukkit.getWorlds()) world.setViewDistance((Integer) value);
                break;
            case "simulation-distance":
                for (World world : Bukkit.getWorlds()) world.setSimulationDistance((Integer) value);
                break;
        }
        logDebug("Applied adjustment: " + setting + " = " + value);
    }

    private void logDebug(String message) {
        if (debugLogging) getLogger().info("[DEBUG] " + message);
    }

    private double getAverageTPS() {
        if (tpsHistory.isEmpty()) return 20.0;
        return tpsHistory.stream().mapToDouble(Double::doubleValue).average().orElse(20.0);
    }

    class TPSMonitorTask extends BukkitRunnable {
        @Override
        public void run() {
            double currentTPS = Bukkit.getTPS()[0];
            tpsHistory.add(currentTPS);
            if (tpsHistory.size() > tpsAverageWindow) tpsHistory.removeFirst();
            double avgTPS = getAverageTPS();

            if (villagerAINerfThreshold > 0 && villagerAIUnnerfThreshold > 0) {
                if (!villagersNerfed && avgTPS < villagerAINerfThreshold) {
                    toggleVillagerAI(false);
                    villagersNerfed = true;
                } else if (villagersNerfed && avgTPS > villagerAIUnnerfThreshold) {
                    toggleVillagerAI(true);
                    villagersNerfed = false;
                }
            }

            long now = Bukkit.getCurrentTick();
            if (now - lastAdjustmentTime < adjustmentCooldownTicks) return;

            if (avgTPS < tpsNerfThreshold) {
                applyNerfAdjustments();
                lastAdjustmentTime = now;
            } else if (avgTPS > tpsUnnerfThreshold) {
                applyUnnerfAdjustments();
                lastAdjustmentTime = now;
            }
        }

        private void applyNerfAdjustments() {
            for (String setting : originalValues.keySet()) {
                if (!enabledSettings.getOrDefault(setting, false)) continue;
                Object current = currentValues.get(setting);
                Object original = originalValues.get(setting);
                if (current instanceof Number) {
                    String direction = nerfDirections.getOrDefault(setting, "decrease");
                    boolean shouldIncrease = direction.equals("increase");
                    adjustSetting(setting, shouldIncrease);
                }
            }
        }

        private void applyUnnerfAdjustments() {
            for (String setting : originalValues.keySet()) {
                if (!enabledSettings.getOrDefault(setting, false)) continue;
                Object current = currentValues.get(setting);
                Object original = originalValues.get(setting);
                if (!current.equals(original) && current instanceof Number) {
                    Number step = stepSizes.get(setting);
                    if (step == null) continue;
                    if (current instanceof Integer) {
                        int curr = (Integer) current;
                        int orig = (Integer) original;
                        int newVal = (curr < orig) ? curr + step.intValue() : curr - step.intValue();
                        newVal = (curr < orig) ? Math.min(newVal, orig) : Math.max(newVal, orig);
                        applyAdjustment(setting, newVal);
                    }
                }
            }
        }

        private void adjustSetting(String setting, boolean increase) {
            if (!enabledSettings.getOrDefault(setting, false)) return;
            Object current = currentValues.get(setting);
            Number min = minValues.get(setting);
            Number max = maxValues.get(setting);
            Number step = stepSizes.get(setting);
            if (min == null || max == null || step == null) return;

            if (current instanceof Integer) {
                int curr = (Integer) current;
                int minI = min.intValue();
                int maxI = max.intValue();
                int stepI = step.intValue();
                int newVal = increase ? curr + stepI : curr - stepI;
                newVal = Math.max(minI, Math.min(maxI, newVal));
                if (newVal != curr) applyAdjustment(setting, newVal);
            }
        }
    }

    private void toggleVillagerAI(boolean enable) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    for (Villager v : world.getEntitiesByClass(Villager.class)) {
                        v.setAI(enable);
                    }
                }
            }
        }.runTask(this);
    }

    class EntityDespawnEnforcer extends BukkitRunnable {
        @Override
        public void run() {
            Number hardObj = (Number) currentValues.get("despawn-ranges-monster-hard");
            if (hardObj == null) return;
            int hard = hardObj.intValue();

            for (World world : Bukkit.getWorlds()) {
                if (world.getPlayers().isEmpty()) continue;
                for (Monster e : world.getEntitiesByClass(Monster.class)) {
                    if (e.isPersistent() || e.isInsideVehicle()) continue;
                    double dist = world.getPlayers().stream()
                            .mapToDouble(p -> p.getLocation().distance(e.getLocation()))
                            .min().orElse(Double.MAX_VALUE);
                    if (dist > hard) e.remove();
                }
            }
        }
    }

    class ItemMergerTask extends BukkitRunnable {
        @Override
        public void run() {
            Number r = (Number) currentValues.get("merge-radius-item");
            if (r == null) return;
            double radius = r.doubleValue();
            for (World w : Bukkit.getWorlds()) {
                Collection<Item> items = w.getEntitiesByClass(Item.class);
                for (Item item : items) {
                    if (!item.isValid()) continue;
                    for (Entity e : item.getNearbyEntities(radius, radius, radius)) {
                        if (e instanceof Item other && other.isValid() && other.getItemStack().isSimilar(item.getItemStack())) {
                            ItemStack stack = item.getItemStack();
                            stack.setAmount(stack.getAmount() + other.getItemStack().getAmount());
                            item.setItemStack(stack);
                            other.remove();
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Number rateObj = (Number) currentValues.get("item-despawn-rate");
        if (rateObj == null) return;
        event.getEntity().setTicksLived(Math.max(1, 6000 - rateObj.intValue()));
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) return;

        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            Number capObj = (Number) currentValues.get("global-monster-cap");
            if (capObj != null) {
                int cap = capObj.intValue();
                long currentMonsters = event.getLocation().getWorld().getEntitiesByClass(Monster.class).size();
                if (currentMonsters >= cap) {
                    event.setCancelled(true);
                    return;
                }
            }

            Number rangeObj = (Number) currentValues.get("mob-spawn-range");
            if (rangeObj != null) {
                int range = rangeObj.intValue();
                boolean inRange = event.getLocation().getWorld().getPlayers().stream()
                        .anyMatch(p -> p.getLocation().distance(event.getLocation()) <= range * 16);
                if (!inRange) event.setCancelled(true);
            }
        }
    }
}
