package me.zpleum.zFL;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.EulerAngle;

import java.util.*;
import java.util.logging.Logger;

public class ZFL extends JavaPlugin {
    private static final int MAX_LEAVES_MULTIPLIER = 5;
    private static final int MIN_LEAVES_SPAWN = 2;
    private static final int MAX_LEAVES_SPAWN = 4;
    private static final int GROUND_REMOVAL_DELAY = 100;

    private final Random random = new Random();
    private final Map<String, WorldLeafConfig> worldConfigs = new HashMap<>();
    private final Map<String, List<FallingLeaf>> activeLeavesByWorld = new HashMap<>();
    private final Logger logger = getLogger();
    private int taskId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        clearOldLeaves();

        taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (String worldName : worldConfigs.keySet()) {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    spawnNewLeaves(world);
                    updateLeaves(world);
                }
            }
        }, 0L, 2L).getTaskId();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTask(taskId);
        clearOldLeaves();
    }

    private void loadConfig() {
        worldConfigs.clear();
        ConfigurationSection worldsSection = getConfig().getConfigurationSection("worlds");

        if (worldsSection == null) {
            createDefaultWorldConfig();
            return;
        }

        for (String worldName : worldsSection.getKeys(false)) {
            ConfigurationSection worldSection = worldsSection.getConfigurationSection(worldName);
            if (worldSection == null || !worldSection.getBoolean("enabled", true)) continue;

            try {
                WorldLeafConfig config = createWorldLeafConfig(worldSection);
                worldConfigs.put(worldName, config);
                activeLeavesByWorld.put(worldName, new ArrayList<>());
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid config for world " + worldName + ": " + e.getMessage());
            }
        }
    }

    private WorldLeafConfig createWorldLeafConfig(ConfigurationSection worldSection) {
        return new WorldLeafConfig(
                validateIntConfig(worldSection, "amount", 10),
                validateDoubleConfig(worldSection, "min-height", 70),
                validateDoubleConfig(worldSection, "max-height", 100),
                validateDoubleConfig(worldSection, "fall-speed-min", 0.02),
                validateDoubleConfig(worldSection, "fall-speed-max", 0.05),
                validateDoubleConfig(worldSection, "wind-speed-min", 0.01),
                validateDoubleConfig(worldSection, "wind-speed-max", 0.03),
                validateDoubleConfig(worldSection, "size-min", 0.5),
                validateDoubleConfig(worldSection, "size-max", 2.0)
        );
    }

    private int validateIntConfig(ConfigurationSection section, String path, int defaultValue) {
        int value = section.getInt(path, defaultValue);
        if (value < 0) {
            logger.warning("Invalid " + path + " value. Using default: " + defaultValue);
            return defaultValue;
        }
        return value;
    }

    private double validateDoubleConfig(ConfigurationSection section, String path, double defaultValue) {
        double value = section.getDouble(path, defaultValue);
        if (value < 0) {
            logger.warning("Invalid " + path + " value. Using default: " + defaultValue);
            return defaultValue;
        }
        return value;
    }

    private void createDefaultWorldConfig() {
        WorldLeafConfig defaultConfig = new WorldLeafConfig(
                10, 70, 100,
                0.02, 0.05, 0.01, 0.03,
                0.5, 2.0
        );
        worldConfigs.put("world", defaultConfig);

        ConfigurationSection worldSection = getConfig().createSection("worlds.world");
        worldSection.set("enabled", true);
        worldSection.set("amount", 10);
        worldSection.set("min-height", 70);
        worldSection.set("max-height", 100);

        saveConfig();
    }

    private void clearOldLeaves() {
        for (String worldName : worldConfigs.keySet()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            world.getEntities().stream()
                    .filter(entity -> entity instanceof ArmorStand)
                    .filter(entity -> {
                        ArmorStand stand = (ArmorStand) entity;
                        return !stand.isVisible() && stand.getEquipment() != null &&
                                stand.getEquipment().getHelmet() != null &&
                                stand.getEquipment().getHelmet().getType() == Material.KELP;
                    })
                    .forEach(Entity::remove);
        }
    }

    private void spawnNewLeaves(World world) {
        WorldLeafConfig config = worldConfigs.get(world.getName());
        if (config == null) return;

        List<FallingLeaf> activeLeaves = activeLeavesByWorld.computeIfAbsent(
                world.getName(), k -> new ArrayList<>());

        int maxLeaves = config.amount * MAX_LEAVES_MULTIPLIER;
        if (activeLeaves.size() >= maxLeaves) return;

        int leavesToSpawn = random.nextInt(MAX_LEAVES_SPAWN - MIN_LEAVES_SPAWN + 1) + MIN_LEAVES_SPAWN;
        for (int i = 0; i < leavesToSpawn; i++) {
            double x = (random.nextDouble() * 200) - 100;
            double z = (random.nextDouble() * 200) - 100;
            double y = random.nextDouble() * (config.maxHeight - config.minHeight) + config.minHeight;

            Location loc = new Location(world, x, y, z);
            spawnLeaf(loc, config);
        }
    }

    private void spawnLeaf(Location location, WorldLeafConfig config) {
        World world = location.getWorld();
        if (world == null) return;

        ArmorStand armorStand = world.spawn(location, ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.getEquipment().setHelmet(new ItemStack(Material.KELP));

        double size = randomInRange(config.minSize, config.maxSize);
        armorStand.setSmall(size < 1.0);

        EulerAngle initialPose = new EulerAngle(0, 0, 0);
        armorStand.setHeadPose(initialPose);

        FallingLeaf leaf = new FallingLeaf(
                armorStand,
                randomInRange(config.fallSpeedMin, config.fallSpeedMax),
                randomInRange(config.windSpeedMin, config.windSpeedMax) * (random.nextBoolean() ? -1 : 1),
                randomInRange(config.windSpeedMin, config.windSpeedMax) * (random.nextBoolean() ? -1 : 1),
                (random.nextDouble() * 4) - 2,
                (random.nextDouble() * 10) - 5,
                size
        );

        activeLeavesByWorld.get(world.getName()).add(leaf);
    }

    private void updateLeaves(World world) {
        List<FallingLeaf> activeLeaves = activeLeavesByWorld.get(world.getName());
        if (activeLeaves == null) return;

        WorldLeafConfig config = worldConfigs.get(world.getName());
        if (config == null) return;

        Iterator<FallingLeaf> iterator = activeLeaves.iterator();
        while (iterator.hasNext()) {
            FallingLeaf leaf = iterator.next();

            if (leaf.armorStand.isDead() || leaf.armorStand.getLocation().getY() <= config.minHeight) {
                placeLeafOnGround(leaf.armorStand);
                iterator.remove();
                continue;
            }

            Location currentLocation = leaf.armorStand.getLocation();
            currentLocation.add(leaf.windSpeedX, -leaf.fallSpeed, leaf.windSpeedZ);
            leaf.armorStand.teleport(currentLocation);

            leaf.armorStand.setRotation(
                    (float) ((leaf.armorStand.getLocation().getYaw() + leaf.rotationSpeed) % 360),
                    0
            );

            double rotationMultiplier = 1.0 / leaf.size;
            EulerAngle headPose = leaf.armorStand.getHeadPose();
            leaf.armorStand.setHeadPose(headPose.add(0, 0, Math.toRadians(leaf.verticalRotationSpeed * rotationMultiplier)));
        }
    }

    private void placeLeafOnGround(ArmorStand armorStand) {
        Location location = armorStand.getLocation();
        location.setPitch(90);
        armorStand.teleport(location);

        Bukkit.getScheduler().runTaskLater(this, armorStand::remove, GROUND_REMOVAL_DELAY);
    }

    private double randomInRange(double min, double max) {
        return min + (random.nextDouble() * (max - min));
    }

    private static class WorldLeafConfig {
        final int amount;
        final double minHeight, maxHeight;
        final double fallSpeedMin, fallSpeedMax;
        final double windSpeedMin, windSpeedMax;
        final double minSize, maxSize;

        WorldLeafConfig(int amount, double minHeight, double maxHeight,
                        double fallSpeedMin, double fallSpeedMax,
                        double windSpeedMin, double windSpeedMax,
                        double minSize, double maxSize) {
            this.amount = amount;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.fallSpeedMin = fallSpeedMin;
            this.fallSpeedMax = fallSpeedMax;
            this.windSpeedMin = windSpeedMin;
            this.windSpeedMax = windSpeedMax;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }
    }

    private static class FallingLeaf {
        final ArmorStand armorStand;
        final double fallSpeed;
        final double windSpeedX;
        final double windSpeedZ;
        final double rotationSpeed;
        final double verticalRotationSpeed;
        final double size;

        FallingLeaf(ArmorStand armorStand, double fallSpeed, double windSpeedX, double windSpeedZ,
                    double rotationSpeed, double verticalRotationSpeed, double size) {
            this.armorStand = armorStand;
            this.fallSpeed = fallSpeed;
            this.windSpeedX = windSpeedX;
            this.windSpeedZ = windSpeedZ;
            this.rotationSpeed = rotationSpeed;
            this.verticalRotationSpeed = verticalRotationSpeed;
            this.size = size;
        }
    }
}