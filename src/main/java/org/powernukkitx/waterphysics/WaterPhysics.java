package org.powernukkitx.waterphysics;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.block.Block;
import org.powernukkitx.block.BlockAir;
import org.powernukkitx.block.BlockFlowingWater;
import org.powernukkitx.block.BlockLiquid;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.PluginCommand;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.block.BlockBreakEvent;
import org.powernukkitx.event.block.BlockPlaceEvent;
import org.powernukkitx.event.block.BlockUpdateEvent;
import org.powernukkitx.event.block.LiquidFlowEvent;
import org.powernukkitx.event.player.PlayerBucketEmptyEvent;
import org.powernukkitx.event.level.ChunkLoadEvent;
import org.powernukkitx.event.level.ChunkUnloadEvent;
import org.powernukkitx.level.Level;
import org.powernukkitx.level.format.IChunk;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.plugin.annotation.PluginMeta;
import org.powernukkitx.scheduler.TaskHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PowerNukkitX port of DeelTer's finite, mass-conserving WaterPhysics engine.
 * One water block contains 1..8 units; every update only transfers those units.
 */
@PluginMeta(
        name = "WaterPhysics",
        version = "1.0.0-PNX",
        api = {"3.0.0"},
        authors = {"deelter", "PowerNukkitX port"},
        description = "Finite, mass-conserving water physics for PowerNukkitX",
        website = "https://github.com/DeelTer/WaterPhysics"
)
public final class WaterPhysics extends PluginBase implements Listener {
    private final WaterQueue queue = new WaterQueue();
    private FlowEngine engine;
    private TaskHandler engineTask;
    private boolean physicsEnabled;
    private int batchSize;
    private int tickInterval;
    private int levelLookahead;
    private boolean proximityCheck;
    private int proximityRadius;
    private Set<String> worlds = Set.of("*");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommand();
        restartEngine();
    }

    private void registerCommand() {
        PluginCommand<WaterPhysics> command = new PluginCommand<>("waterphysics", this);
        command.setAliases(new String[]{"wp", "water"});
        command.setDescription("Manage WaterPhysics");
        command.setUsage("/wp <reload|enable|disable|stop|status>");
        command.setPermission("waterphysics.admin");
        command.setExecutor(this::onCommand);
        getServer().getCommandMap().register(getName().toLowerCase(Locale.ROOT), command);
    }

    @Override
    public void onDisable() {
        stopEngine();
        queue.clear();
    }

    private void loadSettings() {
        physicsEnabled = getConfig().getBoolean("enabled", true);
        batchSize = clamp(getConfig().getInt("flow.batch-size", 512), 1, 100_000);
        tickInterval = clamp(getConfig().getInt("flow.tick-interval", 3), 1, 1200);
        levelLookahead = clamp(getConfig().getInt("flow.level-lookahead", 16), 2, 32);
        proximityCheck = getConfig().getBoolean("optimization.player-proximity-check", true);
        proximityRadius = clamp(getConfig().getInt("optimization.player-proximity-chunks", 4), 0, 32);
        var configured = getConfig().getStringList("worlds");
        if (configured == null || configured.isEmpty()) {
            worlds = Set.of("*");
        } else {
            Set<String> normalized = new HashSet<>();
            configured.forEach(name -> normalized.add(name.toLowerCase(Locale.ROOT)));
            worlds = Set.copyOf(normalized);
        }
    }

    private boolean manages(Level level) {
        return physicsEnabled && (worlds.contains("*")
                || worlds.contains(level.getName().toLowerCase(Locale.ROOT)));
    }

    private void restartEngine() {
        stopEngine();
        if (!physicsEnabled) return;
        engine = new FlowEngine(queue);
        engineTask = getServer().getScheduler().scheduleRepeatingTask(this, engine, tickInterval);
    }

    private void stopEngine() {
        if (engineTask != null) {
            engineTask.cancel();
            engineTask = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaFlow(LiquidFlowEvent event) {
        BlockLiquid source = event.getSource();
        Level level = source.getLevel();
        if (!manages(level) || !(source instanceof BlockFlowingWater)) return;
        event.setCancelled();
        queue.offer(source);
    }

    /**
     * BlockLiquid performs its decay before/around emitting individual flow
     * attempts. Cancelling only LiquidFlowEvent therefore still allows a thin,
     * unsupported layer to decay to air. Managed water is updated exclusively
     * by our conserving engine, so its vanilla scheduled update must be vetoed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaWaterUpdate(BlockUpdateEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof BlockFlowingWater) || !manages(block.getLevel())) return;
        event.setCancelled();
        queue.offer(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        wakeNextTick(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        wakeNextTick(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        wakeNextTick(event.getLiquid());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (event.getLevel() != null && event.getChunk() != null) {
            queue.defer(event.getLevel(), event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.getLevel() != null && event.getChunk() != null) {
            queue.resume(event.getLevel(), event.getChunk());
        }
    }

    private void wakeNextTick(Block block) {
        if (block == null || !manages(block.getLevel())) return;
        Server.getInstance().getScheduler().scheduleDelayedTask(this, () -> wakeArea(block), 1);
    }

    /** Public integration hook for plugins which change blocks without events. */
    public void wakeArea(Block center) {
        if (center == null || !manages(center.getLevel())) return;
        queue.offerPosition(center.getLevel(), center.getFloorX(), center.getFloorY(), center.getFloorZ());
        queue.offerPosition(center.getLevel(), center.getFloorX(), center.getFloorY() + 1, center.getFloorZ());
        queue.offerPosition(center.getLevel(), center.getFloorX(), center.getFloorY() - 1, center.getFloorZ());
        queue.offerPosition(center.getLevel(), center.getFloorX() + 1, center.getFloorY(), center.getFloorZ());
        queue.offerPosition(center.getLevel(), center.getFloorX() - 1, center.getFloorY(), center.getFloorZ());
        queue.offerPosition(center.getLevel(), center.getFloorX(), center.getFloorY(), center.getFloorZ() + 1);
        queue.offerPosition(center.getLevel(), center.getFloorX(), center.getFloorY(), center.getFloorZ() - 1);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("waterphysics.admin")) return false;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                queue.clear();
                restartEngine();
                sender.sendMessage("§aWaterPhysics configuration reloaded.");
            }
            case "enable" -> {
                physicsEnabled = true;
                restartEngine();
                sender.sendMessage("§aWaterPhysics enabled.");
            }
            case "disable", "stop" -> {
                physicsEnabled = false;
                stopEngine();
                queue.clear();
                sender.sendMessage("§eWaterPhysics disabled.");
            }
            case "status" -> sender.sendMessage("§bWaterPhysics: "
                    + (physicsEnabled ? "§aON" : "§cOFF") + "§b, queued=" + queue.size());
            default -> sender.sendMessage("§cUsage: /wp <reload|enable|disable|stop|status>");
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Entry(Level level, int x, int y, int z, int layer, long key) { }

    private static final class WaterQueue {
        // LinkedHashSet gives us FIFO iteration as well as O(1) removal when a
        // complete chunk is unloaded. ArrayDeque.removeIf used to scan the
        // entire global queue for every chunk unload.
        private final LinkedHashSet<Entry> entries = new LinkedHashSet<>();
        private final Map<Integer, Set<Long>> queued = new HashMap<>();
        private final Map<ChunkKey, Set<Entry>> byChunk = new HashMap<>();
        private final Map<ChunkKey, ArrayDeque<Entry>> deferred = new HashMap<>();
        private record ChunkKey(int levelId, int x, int z) { }

        synchronized void offer(Block block) {
            if (block == null) return;
            offer(block.getLevel(), block.getFloorX(), block.getFloorY(), block.getFloorZ(), block.layer);
        }

        synchronized void offerPosition(Level level, int x, int y, int z) {
            if (level == null || !level.isYInRange(y)) return;
            Block base = level.getBlock(x, y, z, 0);
            offer(level, x, y, z, 0);
            if (base.getWaterloggingLevel() > 0
                    || level.getBlock(x, y, z, 1) instanceof BlockFlowingWater) {
                offer(level, x, y, z, 1);
            }
        }

        synchronized void offer(Level level, int x, int y, int z, int layer) {
            if (level == null || !level.isYInRange(y)) return;
            long key = key(x, y, z);
            int bucket = level.getId() * 2 + layer;
            if (queued.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(key)) {
                Entry entry = new Entry(level, x, y, z, layer, key);
                entries.add(entry);
                ChunkKey chunkKey = new ChunkKey(level.getId(), x >> 4, z >> 4);
                byChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(entry);
            }
        }

        synchronized Entry poll() {
            Entry entry = entries.isEmpty() ? null : entries.removeFirst();
            if (entry != null) {
                int bucket = entry.level.getId() * 2 + entry.layer;
                removeQueued(bucket, entry.key);
                removeFromChunk(entry);
            }
            return entry;
        }

        synchronized int size() { return entries.size(); }

        synchronized void defer(Level level, IChunk chunk) {
            ChunkKey chunkKey = new ChunkKey(level.getId(), chunk.getX(), chunk.getZ());
            ArrayDeque<Entry> parked = deferred.computeIfAbsent(chunkKey, ignored -> new ArrayDeque<>());
            Set<Entry> chunkEntries = byChunk.remove(chunkKey);
            if (chunkEntries == null) return;
            for (Entry entry : chunkEntries) {
                entries.remove(entry);
                int bucket = entry.level.getId() * 2 + entry.layer;
                removeQueued(bucket, entry.key);
                parked.addLast(entry);
            }
        }

        synchronized void resume(Level level, IChunk chunk) {
            ChunkKey chunkKey = new ChunkKey(level.getId(), chunk.getX(), chunk.getZ());
            ArrayDeque<Entry> parked = deferred.remove(chunkKey);
            if (parked == null) return;
            while (!parked.isEmpty()) {
                Entry entry = parked.pollFirst();
                offer(entry.level, entry.x, entry.y, entry.z, entry.layer);
            }
        }

        synchronized void clear() {
            entries.clear();
            queued.clear();
            byChunk.clear();
            deferred.clear();
        }

        private void removeQueued(int bucket, long key) {
            Set<Long> keys = queued.get(bucket);
            if (keys == null) return;
            keys.remove(key);
            if (keys.isEmpty()) queued.remove(bucket);
        }

        private void removeFromChunk(Entry entry) {
            ChunkKey chunkKey = new ChunkKey(entry.level.getId(), entry.x >> 4, entry.z >> 4);
            Set<Entry> chunkEntries = byChunk.get(chunkKey);
            if (chunkEntries == null) return;
            chunkEntries.remove(entry);
            if (chunkEntries.isEmpty()) byChunk.remove(chunkKey);
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfffL);
        }
    }

    private final class FlowEngine implements Runnable {
        private static final int[][] SIDES = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};
        private final WaterQueue queue;
        private record Cell(Block block, int layer) { }
        private record WakePosition(int x, int y, int z) { }

        private FlowEngine(WaterQueue queue) { this.queue = queue; }

        @Override
        public void run() {
            // Only process entries which were already queued when this tick began.
            // Entries created by gravity remain queued for the next engine tick;
            // otherwise a large batch can move water down an entire shaft at once.
            int tickBudget = Math.min(batchSize, queue.size());
            Map<Integer, List<Long>> playerChunks = new HashMap<>();
            for (int processed = 0; processed < tickBudget; processed++) {
                Entry entry = queue.poll();
                if (entry == null) return;
                if (manages(entry.level) && isNearPlayer(entry, playerChunks)) process(entry);
            }
        }

        private boolean isNearPlayer(Entry entry, Map<Integer, List<Long>> cache) {
            if (!proximityCheck) return true;
            int cx = entry.x >> 4;
            int cz = entry.z >> 4;
            List<Long> chunks = cache.computeIfAbsent(entry.level.getId(), ignored -> {
                List<Long> result = new ArrayList<>(entry.level.getPlayers().size());
                for (Player player : entry.level.getPlayers().values()) {
                    int playerChunkX = player.getFloorX() >> 4;
                    int playerChunkZ = player.getFloorZ() >> 4;
                    result.add(((long) playerChunkX << 32) | (playerChunkZ & 0xffffffffL));
                }
                return result;
            });
            for (long chunk : chunks) {
                int playerChunkX = (int) (chunk >> 32);
                int playerChunkZ = (int) chunk;
                if (Math.abs(playerChunkX - cx) <= proximityRadius
                        && Math.abs(playerChunkZ - cz) <= proximityRadius) return true;
            }
            return false;
        }

        private void process(Entry entry) {
            Level level = entry.level;
            Block block = level.getBlock(entry.x, entry.y, entry.z, entry.layer);
            int units = units(block);
            if (units == 0) return;

            if (entry.y > level.getMinHeight()) {
                Cell below = cell(level, entry.x, entry.y - 1, entry.z);
                int belowUnits = units(below.block);
                if (isWater(below.block) || isOpenCell(below)) {
                    int moved = Math.min(units, 8 - belowUnits);
                    if (moved > 0) {
                        boolean keepsFalling = entry.y - 1 > level.getMinHeight()
                                && canAccept(cell(level, entry.x, entry.y - 2, entry.z));
                        place(level, entry.x, entry.y - 1, entry.z, below.layer, belowUnits + moved, keepsFalling);
                        place(level, entry.x, entry.y, entry.z, entry.layer, units - moved, false);
                        wake(level, Set.of(
                                new WakePosition(entry.x, entry.y - 1, entry.z),
                                new WakePosition(entry.x, entry.y, entry.z)));
                        return;
                    }
                }
            }

            int remaining = units;
            Set<WakePosition> changed = new HashSet<>();
            for (int[] side : SIDES) {
                if (remaining <= 1) break;
                int nx = entry.x + side[0];
                int nz = entry.z + side[2];
                Cell neighbour = cell(level, nx, entry.y, nz);
                int neighbourUnits = units(neighbour.block);
                if (!(isWater(neighbour.block) || isOpenCell(neighbour))) continue;
                int difference = remaining - neighbourUnits;
                if (difference >= 2) {
                    // Equalise roughly halfway in one operation. Moving only a
                    // single unit made lakes refill holes ring-by-ring and look
                    // frozen. The transfer remains exactly mass-conserving.
                    int moved = Math.min((difference + 1) / 2, 8 - neighbourUnits);
                    place(level, nx, entry.y, nz, neighbour.layer, neighbourUnits + moved, false);
                    remaining -= moved;
                    changed.add(new WakePosition(nx, entry.y, nz));
                }
            }

            // Extended checks are only needed after cheap local equalisation
            // has stalled. Walk each direction once and compare at powers of
            // two, catching long 8,7,7,6-style slopes without scanning a lake.
            if (remaining == units) {
                for (int[] side : SIDES) {
                    for (int distance = 1; distance <= levelLookahead; distance++) {
                        int fx = entry.x + side[0] * distance;
                        int fz = entry.z + side[2] * distance;
                        Cell far = cell(level, fx, entry.y, fz);
                        if (!isWater(far.block)) break; // never transfer across land/gaps
                        if (distance < 2 || (distance & (distance - 1)) != 0) continue;

                        int farUnits = units(far.block);
                        int difference = remaining - farUnits;
                        if (difference >= 2) {
                            int moved = difference / 2;
                            place(level, fx, entry.y, fz, far.layer, farUnits + moved, false);
                            remaining -= moved;
                            changed.add(new WakePosition(fx, entry.y, fz));
                        } else if (difference <= -2) {
                            int moved = (-difference) / 2;
                            place(level, fx, entry.y, fz, far.layer, farUnits - moved, false);
                            remaining += moved;
                            changed.add(new WakePosition(fx, entry.y, fz));
                        }
                    }
                }
            }
            if (remaining != units) {
                place(level, entry.x, entry.y, entry.z, entry.layer, remaining, false);
                changed.add(new WakePosition(entry.x, entry.y, entry.z));
            }
            wake(level, changed);
        }

        private int units(Block block) {
            if (!(block instanceof BlockFlowingWater water)) return 0;
            // Bit 3 is the Bedrock/Minecraft "falling" flag. The lower three
            // bits still carry the conserved fill level.
            int depth = water.getLiquidDepth() & 7;
            if (depth == 0) return 8;
            return 8 - depth;
        }

        private boolean isWater(Block block) {
            return block instanceof BlockFlowingWater;
        }

        private Cell cell(Level level, int x, int y, int z) {
            Block base = level.getBlock(x, y, z, 0);
            if (isWater(base)) return new Cell(base, 0);
            if (base.getWaterloggingLevel() > 0) {
                return new Cell(level.getBlock(x, y, z, 1), 1);
            }
            return new Cell(base, 0);
        }

        private boolean canAccept(Cell cell) {
            return isWater(cell.block) ? units(cell.block) < 8 : isOpenCell(cell);
        }

        private boolean isOpenCell(Cell cell) {
            return cell.layer == 1 ? cell.block.isAir() : cell.block.canBeFlowedInto();
        }

        private void place(Level level, int x, int y, int z, int layer, int units, boolean falling) {
            Vector3 position = new Vector3(x, y, z);
            if (units <= 0) {
                level.setBlock(position, layer, new BlockAir(), true, false);
                return;
            }
            BlockFlowingWater water = new BlockFlowingWater();
            int depth = units >= 8 ? 0 : 8 - units;
            water.setLiquidDepth(depth | (falling ? 8 : 0));
            level.setBlock(position, layer, water, true, false);
        }

        private void wake(Level level, Set<WakePosition> changed) {
            if (changed.isEmpty()) return;
            Set<WakePosition> affected = new HashSet<>(changed.size() * 4);
            for (WakePosition position : changed) {
                affected.add(position);
                affected.add(new WakePosition(position.x, position.y + 1, position.z));
                affected.add(new WakePosition(position.x, position.y - 1, position.z));
                for (int[] side : SIDES) {
                    affected.add(new WakePosition(position.x + side[0], position.y, position.z + side[2]));
                }
            }
            for (WakePosition position : affected) {
                queue.offerPosition(level, position.x, position.y, position.z);
            }
        }
    }
}
