package dev.blockblocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Makes each nearby player's own client collide with the wall (no rubber-banding). Everything here is packets only: the
 * real world is never modified.
 * <ul>
 * <li>Air cells get a fake barrier block, so no existing block is ever hidden.</li>
 * <li>Cells holding a non-full block (fence, slab, grass, water...) optionally get an invisible client-side shulker
 * instead, so the block stays visible but the client still treats the whole cell as solid.</li>
 * <li>Thin shapes (the copy of iron bars/fences in the air above and below them, and edge barriers) are built from tiny
 * invisible shulkers. A shulker on its own always snaps to the centre of its block, so each stack of tiny shulkers rides
 * an invisible block display, which can be placed anywhere: that puts them exactly where the shape is.</li>
 * </ul>
 * The server-side check in {@link BarrierListener} stays on as a backup.
 */
public class ClientBarriers implements Listener
{
	private static final int RADIUS = 8;
	private static final int RESEND_EVERY = 10; // runs; lets fake blocks survive chunk resends

	private record Pos(int x, int y, int z)
	{
	}

	/**
	 * A stack of {@code count} invisible shulker cubes of side {@code size}, the lowest one's minimum corner at
	 * (x, y, z); all in blocks. A whole cell (size 16, count 1) is one plain shulker; anything smaller
	 * rides an invisible block display, so it can sit anywhere instead of at the centre of a block.
	 */
	private record Column(double x, double y, double z, double size, int count)
	{
		/** A whole-cell shulker at a block position: it needs no vehicle (its snapping to the block centre is harmless). */
		boolean plain()
		{
			return size == 1 && count == 1 && x == Math.floor(x) && y == Math.floor(y) && z == Math.floor(z);
		}
	}

	/** One cell whose thin shape is built from tiny shulkers: footprints {x0, z0, x1, z1} in sixteenths, full cell height. */
	private record ShapeCell(int x, int y, int z, List<int[]> footprints)
	{
	}

	/** What a player should currently be shown. */
	private record Wanted(Set<Pos> air, Map<Pos, BlockData> fake, Set<Column> colliders)
	{
	}

	private static final class State
	{
		String world = "";
		Set<Pos> sent = new HashSet<>();
		/** Entity ids per column: [vehicle id or -1, shulker ids...]. */
		Map<Column, int[]> colliders = new HashMap<>();
		Map<Pos, BlockData> fakeSent = new HashMap<>();
	}

	private final JavaPlugin plugin;
	private final BarrierStore store;
	private final boolean useColliders;
	/** Gives the current MinecraftZombies bridge, or null when "mz-barriers" is off. */
	private final Supplier<MzBridge> mz;
	private final Map<UUID, State> states = new HashMap<>();
	/** Ids of the fake shulkers currently shown to anyone. Read from the network thread, so it must be thread-safe. */
	private final Set<Integer> colliderIds = ConcurrentHashMap.newKeySet();
	/** Players who currently have any fake block or shulker around them (so a click of theirs might hit one). Thread-safe. */
	private final Set<UUID> active = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Location> lastSafe = new HashMap<>();
	private final Set<UUID> capWarned = new HashSet<>();
	private BlockData barrier;
	private int counter;

	public ClientBarriers(JavaPlugin plugin, BarrierStore store, boolean useColliders, Supplier<MzBridge> mz)
	{
		this.plugin = plugin;
		this.store = store;
		this.useColliders = useColliders;
		this.mz = mz;
	}

	/** How many fake shulkers this player is currently shown (main thread). */
	public int colliderCount(UUID player)
	{
		State st = states.get(player);
		if(st == null)
			return 0;
		int n = 0;
		for(Column c : st.colliders.keySet())
			n += c.count();
		return n;
	}

	/**
	 * The cube {minX, minY, minZ, maxX, maxY, maxZ} that one of this player's shulkers stands for, or null if the id isn't
	 * one of theirs. Main thread.
	 */
	public int shulkerCount(UUID player)
	{
		State st = states.get(player);
		if(st == null)
			return 0;
		int n = 0;
		for(int[] ids : st.colliders.values())
			n += ids.length - 1;
		return n;
	}

	public double[] cubeOf(UUID player, int entityId)
	{
		State st = states.get(player);
		if(st == null)
			return null;
		for(Map.Entry<Column, int[]> e : st.colliders.entrySet())
		{
			int[] ids = e.getValue();
			for(int i = 1; i < ids.length; i++)
				if(ids[i] == entityId)
				{
					Column c = e.getKey();
					double y = c.y() + (i - 1) * c.size();
					return new double[] {c.x(), y, c.z(), c.x() + c.size(), y + c.size(), c.z() + c.size()};
				}
		}
		return null;
	}

	/** Players within about a block of one of the shulkers they were sent (updated every 2 ticks). */
	private final Set<UUID> nearColliders = ConcurrentHashMap.newKeySet();

	/** Is this player within about a block of one of their shulkers? Safe to call from any thread (used by the Grim hook). */
	public boolean isNearCollider(UUID player)
	{
		return nearColliders.contains(player);
	}

	private static boolean touchesCollider(Player p, State st)
	{
		if(st.colliders.isEmpty())
			return false;
		BoundingBox reach = p.getBoundingBox().expand(1.0);
		for(Column c : st.colliders.keySet())
			if(c.x() < reach.getMaxX() && c.x() + c.size() > reach.getMinX() && c.z() < reach.getMaxZ() && c.z() + c.size() > reach.getMinZ()
					&& c.y() < reach.getMaxY() && c.y() + c.size() * c.count() > reach.getMinY())
				return true;
		return false;
	}

	/** Is this player near a barrier (do they currently see any fake block/shulker)? Safe to call from any thread. */
	public boolean isActive(UUID player)
	{
		return active.contains(player);
	}

	/** Is this entity id one of our client-side shulkers (not a real entity)? Safe to call from any thread. */
	public boolean isCollider(int entityId)
	{
		return colliderIds.contains(entityId);
	}

	public void start()
	{
		barrier = Bukkit.createBlockData(Material.BARRIER);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
	}

	/** Puts everything back to what the server really has. */
	public void shutdown()
	{
		for(Map.Entry<UUID, State> e : states.entrySet())
		{
			Player p = Bukkit.getPlayer(e.getKey());
			if(p == null || !p.getWorld().getName().equals(e.getValue().world))
				continue;
			for(Pos pos : e.getValue().sent)
				restore(p, pos);
			destroyColliders(p, e.getValue().colliders.values());
		}
		states.clear();
		colliderIds.clear();
		active.clear();
	}

	/**
	 * After a respawn the client may have dropped our shulkers: remove what is left and let the next run send them again.
	 * (They are never re-sent on a timer: re-spawning an entity makes it vanish for a moment, and a player stepping in at
	 * that moment gets shoved out when it comes back.)
	 */
	@EventHandler
	public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event)
	{
		Player p = event.getPlayer();
		State st = states.get(p.getUniqueId());
		if(st == null)
			return;
		destroyColliders(p, st.colliders.values());
		st.colliders.clear();
	}

	/** /block shulkers: apply the new visibility to every shulker already shown, without re-spawning any. */
	public void refreshVisibility()
	{
		for(Map.Entry<UUID, State> e : states.entrySet())
		{
			Player p = Bukkit.getPlayer(e.getKey());
			if(p == null)
				continue;
			for(int[] ids : e.getValue().colliders.values())
				for(int i = 1; i < ids.length; i++)
					ColliderPackets.sendFlags(p, ids[i]);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event)
	{
		active.remove(event.getPlayer().getUniqueId());
		lastSafe.remove(event.getPlayer().getUniqueId());
		capWarned.remove(event.getPlayer().getUniqueId());
		State st = states.remove(event.getPlayer().getUniqueId());
		if(st != null)
			st.colliders.values().forEach(this::forgetIds);
	}

	private void forgetIds(int[] ids)
	{
		for(int i = 1; i < ids.length; i++)
			colliderIds.remove(ids[i]);
	}

	private boolean pushMode()
	{
		return "push".equalsIgnoreCase(plugin.getConfig().getString("shape-mode", "block"));
	}

	/** Push mode: a player touching the copied thin shape is shoved away from it, instead of being blocked by it. */
	private void pushPlayers()
	{
		double strength = plugin.getConfig().getDouble("push-strength", 0.5);
		for(Player p : Bukkit.getOnlinePlayers())
		{
			if(p.hasPermission(BarrierListener.BYPASS) || !store.hasBarriersIn(p.getWorld().getName()))
				continue;
			double[] dir = store.pushFor(p.getWorld(), p.getBoundingBox());
			Location now = p.getLocation();
			if(dir == null)
			{
				lastSafe.put(p.getUniqueId(), now); // somewhere outside every shape: where to go back to
				continue;
			}
			// Back toward the last place outside the shapes. One fixed exit, so standing in a gap between two pieces can't make
			// them push in turn (a loop). Without such a place, away from the piece that was touched.
			Location safe = lastSafe.get(p.getUniqueId());
			double dx = dir[0], dz = dir[1];
			if(safe != null && safe.getWorld() == now.getWorld() && safe.distanceSquared(now) < 36)
			{
				double sx = safe.getX() - now.getX(), sz = safe.getZ() - now.getZ(), len = Math.sqrt(sx * sx + sz * sz);
				if(len > 0.05)
				{
					dx = sx / len;
					dz = sz / len;
				}
			}
			p.setVelocity(new org.bukkit.util.Vector(dx * strength, Math.max(p.getVelocity().getY(), 0.0), dz * strength));
		}
	}

	private void tick()
	{
		long t0 = System.nanoTime();
		try
		{
			tickInner();
		}
		finally
		{
			Perf.add(Perf.CLIENT, t0);
		}
	}

	private void tickInner()
	{
		if(pushMode())
			pushPlayers();
		boolean enabled = plugin.getConfig().getBoolean("client-blocks", true);
		boolean resend = ++counter % RESEND_EVERY == 0;

		for(Player p : Bukkit.getOnlinePlayers())
		{
			State st = states.get(p.getUniqueId());
			Wanted wanted = enabled ? wanted(p) : new Wanted(Set.of(), Map.of(), Set.of());
			if(st == null)
			{
				if(wanted.air().isEmpty() && wanted.fake().isEmpty() && wanted.colliders().isEmpty())
					continue;
				st = new State();
				states.put(p.getUniqueId(), st);
			}

			String world = p.getWorld().getName();
			if(!world.equals(st.world))
			{
				// New world: the client already dropped everything.
				st.sent.clear();
				st.fakeSent.clear();
				st.colliders.values().forEach(this::forgetIds);
				st.colliders.clear();
				st.world = world;
			}

			// Fake barrier blocks (air cells).
			for(Pos old : st.sent)
				if(!wanted.air().contains(old) && !wanted.fake().containsKey(old))
					restore(p, old);
			for(Pos pos : wanted.air())
				if(resend || !st.sent.contains(pos))
					p.sendBlockChange(new Location(p.getWorld(), pos.x(), pos.y(), pos.z()), barrier);
			for(Map.Entry<Pos, BlockData> e : wanted.fake().entrySet())
				if(resend || !e.getValue().equals(st.fakeSent.get(e.getKey())))
					p.sendBlockChange(new Location(p.getWorld(), e.getKey().x(), e.getKey().y(), e.getKey().z()), e.getValue());
			st.fakeSent = new HashMap<>(wanted.fake());
			st.sent = new HashSet<>(wanted.air());
			st.sent.addAll(wanted.fake().keySet());

			// Invisible shulker colliders (non-full blocks, thin shapes, edge plates).
			if(useColliders)
			{
				syncColliders(p, st, wanted.colliders());
				if(touchesCollider(p, st))
					nearColliders.add(p.getUniqueId());
				else
					nearColliders.remove(p.getUniqueId());
			}

			if(st.sent.isEmpty() && st.colliders.isEmpty())
			{
				states.remove(p.getUniqueId());
				nearColliders.remove(p.getUniqueId());
				active.remove(p.getUniqueId());
			}
			else
				active.add(p.getUniqueId());
		}
	}

	private void syncColliders(Player p, State st, Set<Column> wanted)
	{
		List<int[]> gone = new ArrayList<>();
		var it = st.colliders.entrySet().iterator();
		while(it.hasNext())
		{
			var e = it.next();
			if(!wanted.contains(e.getKey()))
			{
				gone.add(e.getValue());
				it.remove();
			}
		}
		destroyColliders(p, gone);

		for(Column c : wanted)
		{
			int[] ids = st.colliders.get(c);
			if(ids == null)
			{
				ids = new int[c.count() + 1];
				ids[0] = c.plain() ? -1 : ColliderPackets.newEntityId();
				for(int i = 1; i < ids.length; i++)
				{
					ids[i] = ColliderPackets.newEntityId();
					colliderIds.add(ids[i]);
				}
				st.colliders.put(c, ids);
				ColliderPackets.spawnColumn(p, ids, c.x(), c.y(), c.z(), c.size());
				Perf.SPAWNED.addAndGet(c.count());
			}
		}
	}

	private void destroyColliders(Player p, Iterable<int[]> columns)
	{
		if(!useColliders)
			return;
		List<Integer> list = new ArrayList<>();
		for(int[] ids : columns)
		{
			forgetIds(ids);
			for(int id : ids)
				if(id >= 0)
					list.add(id);
			Perf.DESTROYED.addAndGet(ids.length - 1);
		}
		ColliderPackets.destroy(p, list.stream().mapToInt(Integer::intValue).toArray());
	}

	private void restore(Player p, Pos pos)
	{
		Location loc = new Location(p.getWorld(), pos.x(), pos.y(), pos.z());
		p.sendBlockChange(loc, loc.getBlock().getBlockData());
	}

	/** What nearby barriers' outer shells need for this player. */
	private Wanted wanted(Player p)
	{
		boolean pushMode = pushMode();
		Set<Pos> air = new HashSet<>();
		Map<Pos, BlockData> fake = new HashMap<>();
		boolean shapeFake = !pushMode && plugin.getConfig().getBoolean("shape-fake-blocks", false);
		Set<Column> colliders = new HashSet<>();
		List<ShapeCell> shaped = new ArrayList<>();
		List<Column> customColumns = new ArrayList<>();
		World w = p.getWorld();
		// BlockBlocker's own barriers, plus (if "mz-barriers" is on) the cells of the MinecraftZombies barriers this player is bound by.
		List<Barrier> barriers = new ArrayList<>(store.barriersIn(w.getName()));
		MzBridge bridge = mz.get();
		if(bridge != null)
			barriers.addAll(bridge.barriersFor(p));
		if(barriers.isEmpty() || p.hasPermission(BarrierListener.BYPASS))
			return new Wanted(air, fake, colliders);

		Location l = p.getLocation();
		int px = l.getBlockX(), py = l.getBlockY(), pz = l.getBlockZ();
		BoundingBox body = p.getBoundingBox().expand(-0.02); // slightly smaller, so touching a wall never counts as being inside it

		for(Barrier b : barriers)
		{
			// Custom barrier: one exact box, built from shulkers placed exactly on it.
			List<double[]> customBoxes = MzBridge.CELL_NAME.equals(b.name()) ? null : store.getCustom(b);
			if(customBoxes != null)
			{
				if(useColliders)
					for(double[] custom : customBoxes)
						if(custom[3] > px - RADIUS && custom[0] < px + RADIUS + 1 && custom[4] > py - RADIUS && custom[1] < py + RADIUS + 1
								&& custom[5] > pz - RADIUS && custom[2] < pz + RADIUS + 1)
							customColumns.addAll(customColumns(custom, body));
				continue;
			}
			BlockFace edge = MzBridge.CELL_NAME.equals(b.name()) ? null : store.getEdge(b);
			// Already inside: leave them alone so they can walk out. (Edge plates are checked one cube at a time instead.)
			if(edge == null && b.overlaps(body) && insideSolid(b, w, body))
				continue;

			int x0 = Math.max(b.minX(), px - RADIUS), x1 = Math.min(b.maxX(), px + RADIUS);
			int y0 = Math.max(Math.max(b.minY(), py - RADIUS), w.getMinHeight()), y1 = Math.min(Math.min(b.maxY(), py + RADIUS), w.getMaxHeight() - 1);
			int z0 = Math.max(b.minZ(), pz - RADIUS), z1 = Math.min(b.maxZ(), pz + RADIUS);

			for(int x = x0; x <= x1; x++)
				for(int z = z0; z <= z1; z++)
				{
					if(!w.isChunkLoaded(x >> 4, z >> 4))
						continue;
					for(int y = y0; y <= y1; y++)
					{
						// Only cells that are really part of the shape, and only its outer surface.
						if(!b.contains(x, y, z) || !b.isShell(x, y, z))
							continue;
						Block block = w.getBlockAt(x, y, z);
						if(edge != null)
						{
							// Edge barrier: a thin plate on one side of the cell, whatever the cell holds (the block stays visible).
							if(useColliders && !BarrierStore.isFullCube(block))
								shaped.add(new ShapeCell(x, y, z, List.of(BarrierStore.edgeFootprint(edge))));
							continue;
						}
						boolean plainAir = block.getType().isAir();
						// A no-collision block (coral fan, plant, torch...) in a shape barrier copies the shape like air does
						if(plainAir || BarrierStore.isOpen(block) && !MzBridge.CELL_NAME.equals(b.name()) && !store.isBlockFill()
								&& store.shapeIn(b, block) != null)
						{
							// Air above/below a thin block copies its shape (BlockBlocker barriers only: MZ blocks the whole cell).
							if(!MzBridge.CELL_NAME.equals(b.name()) && !store.isBlockFill() && store.shapeIn(b, block) != null)
							{
								if(pushMode)
									continue; // push mode: not solid, the server pushes instead
								// shape-fake-blocks (needs the resource pack): an invisible block of the same collision shape, if
								// there is one for this block (panes, fences, walls); anything else (doors...) uses tiny shulkers.
								Block src = store.sourceFor(b, block);
								// (never a fake block over a real one: the fan / plant stays visible, shulkers instead)
								BlockData fd = shapeFake && src != null && plainAir ? fakeFor(src, store.connections(b, block)) : null;
								if(fd != null)
								{
									if(!touchesShape(x, y, z, store.shapeIn(b, block), body))
										fake.put(new Pos(x, y, z), fd);
								}
								else if(useColliders)
									shaped.add(new ShapeCell(x, y, z, store.shapeIn(b, block)));
								continue;
							}
							air.add(new Pos(x, y, z));
						}
						// Blocks smaller than a full cube get a full-cell shulker. Thin blocks (iron bars...) in a shape barrier
						// (/block addshape) collide on their own instead; MZ cells always block the whole cell.
						else if(useColliders && !BarrierStore.isFullCube(block))
						{
							boolean mz = MzBridge.CELL_NAME.equals(b.name());
							if(mz && store.isThin(block))
								continue; // an MZ window of bars/panes/fences/walls still up: its own collision (MZ allows it too)
							if(!mz && store.hasShapes(b) && store.isThin(block))
							{
								// shape barrier: bars, fences... collide on their own, plus an arm bridging the gap to a thin
								// neighbour they don't join by themselves (a fence next to iron bars)
								List<int[]> arms = store.thinCellExtras(b, block); // + a thicker block's outline under it
								if(!arms.isEmpty() && !pushMode)
									shaped.add(new ShapeCell(x, y, z, arms));
								continue;
							}
							if(!mz && store.hasShapes(b) && store.copiesShape(block))
							{
								// Shape barrier: doors, trapdoors, slabs... keep their footprint over the whole cell height.
								List<int[]> fp = store.footprints(block);
								if(fp != null && !pushMode)
									shaped.add(new ShapeCell(x, y, z, fp));
							}
							else if(!new BoundingBox(x, y, z, x + 1, y + 1, z + 1).overlaps(body))
								colliders.add(new Column(x, y, z, 1, 1));
						}
					}
				}
		}

		// Thin shapes from tiny shulkers: nearest cells first, up to the per-player limit (beyond it the server alone enforces them).
		int cap = plugin.getConfig().getInt("max-shape-colliders", 2500);
		int sideGap = sideGap16();
		shaped.sort(Comparator.comparingDouble(c -> sq(c.x() + 0.5 - l.getX()) + sq(c.y() + 0.5 - l.getY()) + sq(c.z() + 0.5 - l.getZ())));
		List<Column> tiny = new ArrayList<>(customColumns);
		int used = customColumns.size();
		for(ShapeCell c : shaped)
		{
			List<Column> cubes = columnsFor(c, body, sideGap);
			int n = 0;
			for(Column col : cubes)
				n += col.count();
			if(used + n > cap)
			{
				if(capWarned.add(p.getUniqueId()))
					plugin.getLogger().warning("max-shape-colliders (" + cap + ") reached for " + p.getName()
							+ ": thin shapes further away are only enforced by the server (may feel like a pull-back). Raise it in config.yml and run /block reload.");
				break;
			}
			used += n;
			tiny.addAll(cubes);
		}
		colliders.addAll(mergeColumns(tiny));
		return new Wanted(air, fake, colliders);
	}

	private static double sq(double v)
	{
		return v * v;
	}

	/** Largest gap left between two cubes, in sixteenths: well under the smallest player hitbox (0.6 wide/tall, prone). */
	private static final int MAX_GAP_16 = 8;

	/**
	 * Gap between the cubes ALONG a shape (sideways), in sixteenths (config shape-collider-gap, default 2). Small, so the
	 * invisible wall is smooth to walk along and follows the shape closely instead of being a dotted line you sink into.
	 * (Up the cell the gaps stay up to MAX_GAP_16: nobody fits through those, and it keeps the number of cubes down.)
	 */
	private int sideGap16()
	{
		return Math.max(0, Math.min(MAX_GAP_16, plugin.getConfig().getInt("shape-collider-gap", 2)));
	}

	/**
	 * Shulker cubes that keep a cell's thin shape exactly (each cube is as thick as the part of the shape it stands for), but
	 * spread out along it and up the cell instead of packed solid: the gaps between them are at most {@link #MAX_GAP_16},
	 * too small for any player, even prone. Cubes that would sit on the player's own body are left out.
	 */
	private static List<Column> columnsFor(ShapeCell c, BoundingBox body, int sideGap)
	{
		// Thickest parts first, so a thin arm's cube that lies inside a post's cube can be dropped.
		List<int[]> footprints = new ArrayList<>(c.footprints());
		footprints.sort(Comparator.comparingInt((int[] f) -> Math.min(f[2] - f[0], f[3] - f[1])).reversed());
		List<int[]> cubes = new ArrayList<>(); // {x16, y16, z16, size} relative to the cell
		for(int[] f : footprints)
		{
			int size = Math.max(1, Math.min(16, Math.min(f[2] - f[0], f[3] - f[1])));
			for(int mx : corners(f[0], f[2], size, sideGap))
				for(int mz : corners(f[1], f[3], size, sideGap))
					for(int my : corners(0, 16, size, MAX_GAP_16))
					{
						int[] cube = {mx, my, mz, size};
						boolean inside = false;
						for(int[] o : cubes)
							inside |= cube[0] >= o[0] && cube[1] >= o[1] && cube[2] >= o[2]
									&& cube[0] + size <= o[0] + o[3] && cube[1] + size <= o[1] + o[3] && cube[2] + size <= o[2] + o[3];
						if(!inside)
							cubes.add(cube);
					}
		}
		List<Column> out = new ArrayList<>();
		for(int[] cube : cubes)
		{
			int x16 = c.x() * 16 + cube[0], y16 = c.y() * 16 + cube[1], z16 = c.z() * 16 + cube[2];
			if(!cube(x16, y16, z16, cube[3]).overlaps(body))
				out.add(new Column(x16 / 16.0, y16 / 16.0, z16 / 16.0, cube[3] / 16.0, 1));
		}
		return out;
	}

	/**
	 * Where the cubes go along [a, b] (sixteenths), as each cube's LOW corner, so every cube lies exactly inside [a, b]: the
	 * first flush with a, the last flush with b, the rest evenly between, at most `gap` apart. (Placing them by their centre
	 * rounded odd sizes, like a 3/16 door, half a sixteenth off: the last cube poked out of the shape.)
	 */
	private static List<Integer> corners(int a, int b, int size, int gap)
	{
		size = Math.min(size, b - a);
		int span = b - a - size; // how far the low corner can move
		if(span <= 0)
			return List.of(a);
		int n = (span + size + gap - 1) / (size + gap) + 1; // cubes needed so the space between neighbours is <= gap
		List<Integer> out = new ArrayList<>(n);
		for(int i = 0; i < n; i++)
			out.add(a + Math.round(span * i / (float) (n - 1)));
		return out;
	}



	/**
	 * Shulker cubes for a custom box (blocks, decimals allowed): cubes as thick as the box's thinnest side (scale limits:
	 * 1/16 to 1 block), centred on it and spread with gaps of at most half a block, like the thin shapes.
	 */
	private static List<Column> customColumns(double[] box, BoundingBox body)
	{
		double size = Math.max(1 / 16.0, Math.min(1, Math.min(box[3] - box[0], Math.min(box[4] - box[1], box[5] - box[2]))));
		List<Column> out = new ArrayList<>();
		for(double cx : spread(box[0], box[3], size))
			for(double cy : spread(box[1], box[4], size))
				for(double cz : spread(box[2], box[5], size))
				{
					double x = cx - size / 2, y = cy - size / 2, z = cz - size / 2;
					if(!new BoundingBox(x, y, z, x + size, y + size, z + size).overlaps(body))
						out.add(new Column(x, y, z, size, 1));
				}
		return out;
	}

	/** Cube centres along [a, b] (blocks): one in the middle if it fits, else from end to end with gaps of at most half a block. */
	private static List<Double> spread(double a, double b, double size)
	{
		double len = b - a;
		if(len <= size + 1e-9)
			return List.of((a + b) / 2);
		double first = a + size / 2, last = b - size / 2;
		int n = (int) Math.ceil((len - size) / (size + MAX_GAP_16 / 16.0) - 1e-9) + 1;
		List<Double> out = new ArrayList<>(n);
		for(int i = 0; i < n; i++)
			out.add(n == 1 ? first : first + (last - first) * i / (n - 1));
		return out;
	}

	private static BoundingBox cube(int x16, int y16, int z16, int size16)
	{
		return new BoundingBox(x16 / 16.0, y16 / 16.0, z16 / 16.0, (x16 + size16) / 16.0, (y16 + size16) / 16.0, (z16 + size16) / 16.0);
	}

	/** Joins stacks that continue straight up (e.g. the same bar shape in two cells above each other) into one. */
	private static List<Column> mergeColumns(List<Column> columns)
	{
		List<Column> sorted = new ArrayList<>(new HashSet<>(columns));
		sorted.sort(Comparator.comparingDouble(Column::x).thenComparingDouble(Column::z).thenComparingDouble(Column::size).thenComparingDouble(Column::y));
		List<Column> out = new ArrayList<>();
		Column cur = null;
		for(Column c : sorted)
		{
			if(cur != null && c.x() == cur.x() && c.z() == cur.z() && c.size() == cur.size() && Math.abs(c.y() - (cur.y() + cur.count() * cur.size())) < 1e-9)
				cur = new Column(cur.x(), cur.y(), cur.z(), cur.size(), cur.count() + c.count());
			else
			{
				if(cur != null)
					out.add(cur);
				cur = c;
			}
		}
		if(cur != null)
			out.add(cur);
		return out;
	}

	/**
	 * Is the player's body in a cell of this barrier that is really solid-for-them (plain air or a normal block)? Cells that
	 * only hold a thin block (bars...) or the air copy of its shape don't count: a player can stand in those beside the
	 * thin part, and the barrier must stay up around them.
	 */
	private boolean insideSolid(Barrier b, World w, BoundingBox body)
	{
		for(int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++)
			for(int y = (int) Math.floor(body.getMinY()); y <= (int) Math.floor(body.getMaxY()); y++)
				for(int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++)
				{
					if(!b.contains(x, y, z))
						continue;
					Block block = w.getBlockAt(x, y, z);
					if(MzBridge.CELL_NAME.equals(b.name()) || !((store.copiesShape(block) && store.hasShapes(b)) || store.shapeIn(b, block) != null))
						return true;
				}
		return false;
	}

	private final Map<String, Material> fakeMaterials = new HashMap<>();

	/**
	 * The fake block that stands in for the thin block below an air cell: same collision shape, same connections, but a block
	 * the resource pack draws as nothing (see config fake-pane / fake-fence / fake-wall). Null if there is none.
	 */
	private BlockData fakeFor(Block source, List<BlockFace> connect)
	{
		if(source == null)
			return null;
		String n = source.getType().name();
		String key, def;
		if(n.endsWith("BARS") || n.endsWith("GLASS_PANE"))
		{
			key = "fake-pane";
			def = "MAGENTA_STAINED_GLASS_PANE";
		}
		else if(n.endsWith("FENCE"))
		{
			key = "fake-fence";
			def = "CHERRY_FENCE";
		}
		else if(n.endsWith("WALL"))
		{
			key = "fake-wall";
			def = "RESIN_BRICK_WALL";
		}
		else
			return null;
		Material m = fakeMaterials.computeIfAbsent(plugin.getConfig().getString(key, def), name ->
		{
			Material found = Material.matchMaterial(name);
			if(found == null || !found.isBlock())
				plugin.getLogger().warning("config " + key + ": '" + name + "' is not a block; no invisible block for those cells.");
			return found != null && found.isBlock() ? found : Material.AIR;
		});
		if(m.isAir())
			return null;
		BlockData fake = Bukkit.createBlockData(m);
		BlockData src = source.getBlockData();
		if(fake instanceof org.bukkit.block.data.MultipleFacing df && src instanceof org.bukkit.block.data.MultipleFacing sf)
		{
			for(BlockFace f : df.getAllowedFaces())
				df.setFace(f, sf.hasFace(f) || connect.contains(f));
		}
		else if(fake instanceof org.bukkit.block.data.type.Wall dw && src instanceof org.bukkit.block.data.type.Wall sw)
		{
			dw.setUp(sw.isUp());
			for(BlockFace f : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
				dw.setHeight(f, sw.getHeight(f) == org.bukkit.block.data.type.Wall.Height.NONE && connect.contains(f) ? org.bukkit.block.data.type.Wall.Height.LOW : sw.getHeight(f));
		}
		if(fake instanceof org.bukkit.block.data.Waterlogged wl)
			wl.setWaterlogged(false);
		return fake;
	}

	/** Does the player's body overlap the actual thin shape in this cell (not the whole cell)? */
	private static boolean touchesShape(int x, int y, int z, List<int[]> shape, BoundingBox body)
	{
		if(shape == null)
			return false;
		for(int[] f : shape)
			if(new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0).overlaps(body))
				return true;
		return false;
	}
}
