package dev.blockblocker;

import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Makes mobs treat barriers as solid, without placing anything.
 * <ul>
 * <li>Route planning: Minecraft's pathfinder cannot see barriers (they are not blocks). Every few ticks each mob's
 * route is checked; when it runs through a barrier, a route is planned here that treats barrier cells as solid blocks,
 * and the mob is walked along it, the way it would walk around a real wall.</li>
 * <li>Safety net: a move that would still take a mob into a barrier is cut back to the part that does not (it slides
 * along it like along a wall).</li>
 * </ul>
 * Config "mobs-blocked" (true by default) turns it on.
 */
final class MobBlocker implements Listener
{
	/** Most nodes one route search may look at. */
	private static final int MAX_NODES = 4000;
	/** Most route searches per server tick (the rest wait for the next tick), so a crowd of zombies cannot lag the server. */
	private static final int SEARCHES_PER_TICK = 3;
	/** How far (blocks) from the mob a route may lead. */
	private static final int MAX_RANGE = 64;
	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private static final class Plan
	{
		List<int[]> nodes;
		int index;
		Location goal;
		long madeTick;
		/** false: no way around exists, the route only leads as close as possible, and the mob waits at its end. */
		boolean full;
		/** The search ran out of steps (the target is far), not out of ways: walk this far, then look again. */
		boolean cutShort;
		/** Minecraft would not walk it along (it stands where its own pathfinder cannot start): steered directly instead. */
		boolean direct;
		/** The node it was last sent to, and when: it is only sent again when that changes, or now and then. */
		int sentAim = -1;
		long sentTick;
	}

	private record Route(List<int[]> nodes, boolean full, boolean cutShort) {}

	private final JavaPlugin plugin;
	private final BarrierStore store;
	private final Map<UUID, Plan> plans = new HashMap<>();
	/** Mobs whose last search found nothing: tick before which they are not searched again. */
	private final Map<UUID, Long> failedUntil = new HashMap<>();
	private int task = -1;
	private long budgetTick = -1;
	private int searchesLeft;
	/** Minecraft path searches this tick (each one is costly): limited like the route searches. */
	private static final int FIND_PATHS_PER_TICK = 6;
	private long findPathTick = -1;
	private int findPathsLeft;
	/** Worlds (name prefixes) whose mobs another plugin already routes around barriers: no route planning there. */
	private List<String> noPlanWorlds = List.of();

	/** A Minecraft path search, or null when this tick's searches are used up. */
	private Pathfinder.PathResult findPathBudgeted(Mob mob, Location goal)
	{
		long tick = Bukkit.getCurrentTick();
		if(findPathTick != tick)
		{
			findPathTick = tick;
			findPathsLeft = FIND_PATHS_PER_TICK;
		}
		if(findPathsLeft <= 0)
			return null;
		findPathsLeft--;
		return mob.getPathfinder().findPath(goal);
	}

	private boolean planningOff(World world)
	{
		for(String prefix : noPlanWorlds)
			if(world.getName().startsWith(prefix))
				return true;
		return false;
	}

	MobBlocker(JavaPlugin plugin, BarrierStore store)
	{
		this.plugin = plugin;
		this.store = store;
	}

	void start()
	{
		stop();
		if(!plugin.getConfig().getBoolean("mobs-blocked", true))
			return;
		fullForMobs = new java.util.ArrayList<>();
		for(String t : plugin.getConfig().getStringList("mob-full-blocks"))
			if(t != null && !t.isBlank())
				fullForMobs.add(t.trim().toUpperCase(java.util.Locale.ROOT));
		if(!plugin.getConfig().isSet("mob-full-blocks"))
			fullForMobs.add("BARS");
		noPlanWorlds = plugin.getConfig().isSet("no-route-planning-worlds")
				? List.copyOf(plugin.getConfig().getStringList("no-route-planning-worlds")) : List.of("mz_instance_");

		Bukkit.getPluginManager().registerEvents(this, plugin);
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L).getTaskId();
	}

	void stop()
	{
		HandlerList.unregisterAll(this);
		if(task != -1)
			Bukkit.getScheduler().cancelTask(task);
		task = -1;
		plans.clear();
		failedUntil.clear();
	}

	// ------------------------------------------------------------------ safety net

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onMobMove(EntityMoveEvent event)
	{
		if(!event.hasChangedPosition())
			return;
		LivingEntity entity = event.getEntity();
		if(!(entity instanceof Mob mob))
			return;
		World world = mob.getWorld();
		if(!store.hasBarriersIn(world.getName()))
			return;

		Location from = event.getFrom(), to = event.getTo();
		double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
		Location cur = mob.getLocation();
		BoundingBox here = mob.getBoundingBox().shift(from.getX() - cur.getX(), from.getY() - cur.getY(), from.getZ() - cur.getZ());
		// Mobs may not step onto the top of a barrier either (like a fence): standing on it, they would hover above it
		// over thin air. So a move is blocked if the body goes in, or the feet end up just above it. A mob that is already
		// on top (jumped or knocked there) may walk off.
		boolean onTop = !store.intersects(world, here) && store.intersectsEmpty(world, feet(here));
		// The whole space the move passes through, not just where it ends: a fast fall (or knockback) would otherwise skip
		// straight past a thin barrier piece in one tick
		if(!hits(world, swept(here, dx, dy, dz), here))
			return;
		// Already inside one (spawned or pushed in): let it walk out
		if(solid(world, here, here))
			return;

		Location slide = null;
		// Keep whatever part of the move is free: along x, along z, and the vertical part only if that is free too
		boolean yFree = !hits(world, swept(here, 0, dy, 0), here);
		double ky = yFree ? dy : 0;
		// Only the vertical part is in the way (standing on top of a barrier, gravity pulls it down every tick): keep the whole
		// horizontal move. Trying x alone first used to throw the z part away on every such tick, so a mob standing on a low
		// barrier could only ever move along x and never stepped off it.
		if(!yFree && (dx != 0 || dz != 0) && !hits(world, swept(here, dx, 0, dz), here))
			slide = new Location(world, from.getX() + dx, from.getY(), from.getZ() + dz, to.getYaw(), to.getPitch());
		else if(!hits(world, swept(here, dx, ky, 0), here) && dx != 0)
			slide = new Location(world, from.getX() + dx, from.getY() + ky, from.getZ(), to.getYaw(), to.getPitch());
		else if(!hits(world, swept(here, 0, ky, dz), here) && dz != 0)
			slide = new Location(world, from.getX(), from.getY() + ky, from.getZ() + dz, to.getYaw(), to.getPitch());
		else if(yFree && dy != 0)
			slide = new Location(world, from.getX(), from.getY() + dy, from.getZ(), to.getYaw(), to.getPitch());
		if(slide != null)
			event.setTo(slide);
		else
			event.setCancelled(true);
		BoundingBox moved = swept(here, dx, dy, dz);
		debug(mob, "move " + (slide == null ? "stopped" : "cut short") + " by " + (store.nameAt(world, moved) != null ? "barrier " + store.nameAt(world, moved) : "a full-for-mobs block (iron bars...)")
				+ (mob.getTarget() != null ? " (target " + mob.getTarget().getName() + ")" : "") + routeInfo(mob));
		Vector v = mob.getVelocity();
		// No jumping on against it either (a blocked jump would just bounce it up and down)
		// A blocked jump does not carry on upwards, and a blocked fall stops: without that the falling speed kept building up
		// every tick while it stood on the barrier, until one tick's fall was big enough to drop clean through a thin piece.
		double vy = v.getY();
		if(dy != 0 && (slide == null || slide.getY() == from.getY()))
		{
			vy = 0;
			mob.setFallDistance(0);
			// Held up by a barrier: that is standing on the ground. Minecraft only counts real blocks, so without this the
			// mob was "in the air" on top of a barrier: it could not jump (onto the next bar, say) and only crept along.
			if(dy < 0)
				markOnGround(mob);
		}
		// A rising mob (mid-jump) keeps its forward speed: it is still below the edge it is jumping onto, and once above it,
		// that speed carries it on. Zeroing it made every jump go straight up and back down.
		boolean rising = dy > 0 && vy > 0;
		Vector after = new Vector(!rising && (slide == null || slide.getX() == from.getX()) ? 0 : v.getX(), vy,
				!rising && (slide == null || slide.getZ() == from.getZ()) ? 0 : v.getZ());
		// Walked into a low barrier (about a block high, like a rail players step over) with room above it: hop over it, the
		// way it would hop a real block. Minecraft never jumps for a barrier itself, and with a crowd the planned routes
		// cannot keep up, so this is done right here. Taller barriers have no room just above, so they are never hopped.
		Vector hop = hopOver(mob, world, here, dx, dz);
		mob.setVelocity(hop != null ? hop : after);
		// It walked into one: plan a way around now rather than at the next check
		if(!plans.containsKey(mob.getUniqueId()))
			considerPlanning(mob, Bukkit.getCurrentTick());
	}

	/**
	 * Mobs hop onto anything about a block high in their way - iron bars, a fence top - not knowing a barrier sits on top,
	 * then bounce against it over and over. A jump that would end in a barrier (or on top of one) is cancelled and a way
	 * around is planned at once; and a mob following a planned route only jumps where that route really steps up.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onMobJump(com.destroystokyo.paper.event.entity.EntityJumpEvent event)
	{
		if(!(event.getEntity() instanceof Mob mob))
			return;
		World world = mob.getWorld();
		if(!store.hasBarriersIn(world.getName()))
			return;
		long tick = Bukkit.getCurrentTick();
		Plan plan = plans.get(mob.getUniqueId());
		// Where the jump would take it: about 1.25 up and half a block on in the direction it is heading
		Vector dir = mob.getVelocity().setY(0);
		if(dir.lengthSquared() < 1.0E-4)
			dir = mob.getLocation().getDirection().setY(0);
		if(dir.lengthSquared() < 1.0E-4)
			return;
		dir.normalize().multiply(0.5);
		BoundingBox landing = mob.getBoundingBox().shift(dir.getX(), 1.25, dir.getZ());
		if(solid(world, landing))
		{
			debug(mob, "jump cancelled: it would land in barrier " + store.nameAt(world, landing));
			event.setCancelled(true);
			if(plan == null)
			{
				Location goal = goalOf(mob);
				if(goal != null)
					plan(mob, goal, tick);
			}
		}
	}

	private final Map<UUID, Long> lastHop = new HashMap<>();
	/** Material-name endings of blocks that are a full block for mobs (config "mob-full-blocks"). */
	private List<String> fullForMobs = List.of();

	/** Is the block's material one that counts as a full block for mobs? */
	private boolean fullForMob(Block block)
	{
		if(fullForMobs.isEmpty())
			return false;
		String name = block.getType().name();
		for(String t : fullForMobs)
			if(name.endsWith(t))
				return true;
		return false;
	}

	/**
	 * Is this space closed to a mob: inside a barrier, or inside the whole block of something that counts as a full block
	 * for mobs (iron bars by default: only their thin post is solid in Minecraft, so mobs pressed in until their centre stood
	 * in the bars' block, where Minecraft's pathfinder cannot start a route, and they got stuck; its pathfinder already
	 * treats such blocks as full anyway).
	 */
	private boolean solid(World world, BoundingBox box)
	{
		return solid(world, box, null);
	}

	/**
	 * Like {@link #solid(World, BoundingBox)}, for a mob moving from {@code from}: a full-for-mobs block it already overlaps
	 * (it was pressed into the bars before, or stands half in them) does not count, so it is never held in the air by one;
	 * such a block only keeps it from moving into it.
	 */
	private boolean solid(World world, BoundingBox box, BoundingBox from)
	{
		if(store.intersects(world, box))
			return true;
		if(fullForMobs.isEmpty())
			return false;
		for(int x = (int) Math.floor(box.getMinX()); x <= (int) Math.ceil(box.getMaxX()) - 1; x++)
			for(int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.ceil(box.getMaxZ()) - 1; z++)
			{
				if(!world.isChunkLoaded(x >> 4, z >> 4))
					continue;
				for(int y = (int) Math.floor(box.getMinY()); y <= (int) Math.ceil(box.getMaxY()) - 1; y++)
				{
					if(!fullForMob(world.getBlockAt(x, y, z)))
						continue;
					if(from != null && from.overlaps(new BoundingBox(x, y, z, x + 1, y + 1, z + 1)))
						continue;
					return true;
				}
			}
		return false;
	}
	private static java.lang.reflect.Method getHandle, setOnGround;
	private static boolean onGroundBroken;

	/** Tells Minecraft the mob stands on the ground (there is no API for it, so via the server's own entity). */
	private void markOnGround(Mob mob)
	{
		if(onGroundBroken)
			return;
		try
		{
			if(getHandle == null)
				getHandle = mob.getClass().getMethod("getHandle");
			Object handle = getHandle.invoke(mob);
			if(setOnGround == null)
				setOnGround = handle.getClass().getMethod("setOnGround", boolean.class);
			setOnGround.invoke(handle, true);
		}
		catch(ReflectiveOperationException | RuntimeException e)
		{
			onGroundBroken = true;
			plugin.getLogger().warning("Could not mark mobs standing on barriers as on the ground (" + e + "); they may not jump from them.");
		}
	}
	private final Map<UUID, Long> lastDebug = new HashMap<>();

	private String routeInfo(Mob mob)
	{
		Plan p = plans.get(mob.getUniqueId());
		if(p == null || p.index >= p.nodes.size())
			return "";
		int[] n = p.nodes.get(p.index);
		int[] first = p.nodes.get(0);
		return String.format(" [route from %d %d %d, next step %d %d %d (%d of %d)%s]", first[0], first[1], first[2], n[0], n[1], n[2],
				p.index, p.nodes.size() - 1, p.direct ? ", steered directly" : "");
	}

	/** With "mob-debug: true": why a mob was stopped, at most once a second per mob. */
	private void debug(Mob mob, String what)
	{
		if(!plugin.getConfig().getBoolean("mob-debug", false))
			return;
		long tick = Bukkit.getCurrentTick();
		Long last = lastDebug.get(mob.getUniqueId());
		if(last != null && tick - last < 20)
			return;
		lastDebug.put(mob.getUniqueId(), tick);
		if(lastDebug.size() > 500)
			lastDebug.values().removeIf(t -> tick - t > 200);
		Location l = mob.getLocation();
		plugin.getLogger().info(String.format("[mob %s @ %.2f %.2f %.2f%s] %s", mob.getType().name().toLowerCase(java.util.Locale.ROOT),
				l.getX(), l.getY(), l.getZ(), plans.containsKey(mob.getUniqueId()) ? ", on planned route" : "", what));
	}

	/** A jump over a low barrier right ahead (see onMobMove), or null when there is none to hop. */
	private Vector hopOver(Mob mob, World world, BoundingBox here, double dx, double dz)
	{
		double len = Math.sqrt(dx * dx + dz * dz);
		if(len < 1.0E-3 || !mob.isOnGround())
			return null;
		long tick = Bukkit.getCurrentTick();
		Long last = lastHop.get(mob.getUniqueId());
		if(last != null && tick - last < 10)
			return null;
		double nx = dx / len, nz = dz / len;
		BoundingBox ahead = here.clone().shift(nx * 0.4, 0, nz * 0.4);
		if(!solid(world, ahead))
			return null; // not a barrier in the way at its feet
		// Room a jump's height up, both where it is and just ahead, free of barriers and of real blocks
		BoundingBox up = here.clone().shift(0, 1.1, 0), upAhead = ahead.clone().shift(0, 1.1, 0);
		if(solid(world, up) || solid(world, upAhead) || world.hasCollisionsIn(up) || world.hasCollisionsIn(upAhead))
			return null;
		lastHop.put(mob.getUniqueId(), tick);
		debug(mob, "hops over barrier " + store.nameAt(world, ahead));
		if(lastHop.size() > 500)
			lastHop.values().removeIf(t -> tick - t > 200);
		return new Vector(nx * 0.22, 0.45, nz * 0.22);
	}

	/** The space a box sweeps through moving by dx/dy/dz (its start and end, and everything between). */
	private static BoundingBox swept(BoundingBox box, double dx, double dy, double dz)
	{
		return box.clone().union(box.clone().shift(dx, dy, dz));
	}

	/** A thin layer just under the feet of a box. */
	private static BoundingBox feet(BoundingBox box)
	{
		return new BoundingBox(box.getMinX(), box.getMinY() - 0.6, box.getMinZ(), box.getMaxX(), box.getMinY() - 0.01, box.getMaxZ());
	}

	/**
	 * Would a mob with this box be in a barrier, or (unless it is already on top of one) standing on an empty barrier cell?
	 * A barrier cell holding a real block (iron bars in a floor) is fine to stand on: the block carries the mob.
	 */
	private boolean hits(World world, BoundingBox box, BoundingBox here)
	{
		// Barriers act like real blocks: a mob may not be inside one, but it may stand on top of any of them (it is marked as
		// on the ground there, so it walks and jumps as on a block).
		return solid(world, box, here);
	}

	// ------------------------------------------------------------------ route planning

	private void tick()
	{
		long tick = Bukkit.getCurrentTick();
		for(World world : Bukkit.getWorlds())
		{
			if(!store.hasBarriersIn(world.getName()))
				continue;
			// MZ arena copies: MZ plans its zombies' routes itself (barriers included). Planning them here too made both
			// plugins steer the same zombies, and cost half the server's time with a crowd of them. They are still blocked.
			if(planningOff(world))
				continue;
			for(Mob mob : world.getEntitiesByClass(Mob.class))
			{
				UUID id = mob.getUniqueId();
				Plan plan = plans.get(id);
				if((tick + id.hashCode()) % 20 == 0 && checkStuck(mob, tick))
					continue;
				if(plan != null)
					follow(mob, plan, tick);
				else if((tick + id.hashCode()) % 5 == 0)
					considerPlanning(mob, tick);
			}
		}
		plans.keySet().removeIf(id -> !(Bukkit.getEntity(id) instanceof Mob m) || !m.isValid());
		failedUntil.values().removeIf(until -> until < tick);
	}

	/** Where each mob was at its last stuck check: x, y, z, and how many checks in a row it barely moved. */
	private final Map<UUID, double[]> lastSpot = new HashMap<>();

	/**
	 * Once a second: a mob next to a barrier that has barely moved for two seconds while it has somewhere to go is stuck
	 * (Minecraft's own route ran it into the barrier, or it stands somewhere its own pathfinder cannot start from). Plan a
	 * route for it now, whatever its own route says; if there is none, hop towards where it wants to go.
	 *
	 * @return true when it was handled (nothing else to do for it this tick)
	 */
	private boolean checkStuck(Mob mob, long tick)
	{
		Location at = mob.getLocation();
		double[] last = lastSpot.get(mob.getUniqueId());
		int still = 0;
		if(last != null && Math.abs(last[0] - at.getX()) < 0.35 && Math.abs(last[1] - at.getY()) < 0.5 && Math.abs(last[2] - at.getZ()) < 0.35)
			still = (int) last[3] + 1;
		lastSpot.put(mob.getUniqueId(), new double[] {still == 0 ? at.getX() : last[0], still == 0 ? at.getY() : last[1], still == 0 ? at.getZ() : last[2], still});
		if(lastSpot.size() > 1000)
			lastSpot.keySet().removeIf(id -> !(Bukkit.getEntity(id) instanceof Mob m) || !m.isValid());
		if(still < 2)
			return false;
		Location goal = goalOf(mob);
		if(goal == null || goal.distanceSquared(at) < 6.25)
			return false;
		BoundingBox box = mob.getBoundingBox();
		if(!store.near(mob.getWorld(), box.clone().expand(1.5)))
			return false;
		debug(mob, "stuck for " + still + "s next to barrier " + store.nameAt(mob.getWorld(), box.clone().expand(1.5)) + ": planning a way");
		failedUntil.remove(mob.getUniqueId());
		Plan old = plans.get(mob.getUniqueId());
		if(old != null && !old.direct && old.full)
		{
			// It has a route but does not follow it: Minecraft will not walk it from here. Steer it by hand.
			old.direct = true;
			lastSpot.put(mob.getUniqueId(), new double[] {at.getX(), at.getY(), at.getZ(), 0});
			debug(mob, "not following its route: steering it directly");
			return true;
		}
		if(plan(mob, goal, tick))
		{
			Plan now = plans.get(mob.getUniqueId());
			// No route at all (or only one that keeps it here): hop towards the goal, it may be wedged on something
			if(now == null || now == old || !now.full && now.nodes.size() <= 2)
				nudge(mob, goal, tick);
		}
		lastSpot.put(mob.getUniqueId(), new double[] {at.getX(), at.getY(), at.getZ(), 0});
		return true;
	}

	/**
	 * The block the route starts from. Not simply the one its centre is in: a mob wedged at the edge of a gap has its centre
	 * over the wall beside it (bars, a barrier), and a route from there leads into that wall. So of the blocks its body
	 * stands in, the one it can really stand in that is closest to its centre.
	 */
	private int[] startCell(Mob mob)
	{
		World world = mob.getWorld();
		BoundingBox b = mob.getBoundingBox();
		Location at = mob.getLocation();
		int y0 = (int) Math.floor(b.getMinY() + 0.2);
		Map<Long, Boolean> cache = new HashMap<>();
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for(int x = (int) Math.floor(b.getMinX()); x <= (int) Math.floor(b.getMaxX() - 1.0E-6); x++)
			for(int z = (int) Math.floor(b.getMinZ()); z <= (int) Math.floor(b.getMaxZ() - 1.0E-6); z++)
				for(int dy : new int[] {0, 1, -1})
				{
					if(!standable(world, x, y0 + dy, z, cache))
						continue;
					double ddx = x + 0.5 - at.getX(), ddz = z + 0.5 - at.getZ();
					double d = ddx * ddx + ddz * ddz + Math.abs(dy) * 0.5;
					if(d < bestD)
					{
						bestD = d;
						best = new int[] {x, y0 + dy, z};
					}
				}
		return best != null ? best : new int[] {at.getBlockX(), y0, at.getBlockZ()};
	}

	/** Walks the mob straight towards a route step (its centre), without Minecraft's pathfinder. */
	private void steerTo(Mob mob, int[] node)
	{
		Location at = mob.getLocation();
		// In the air it is only carried forward once it is up at the step's height (not into the step's side)
		if(!mob.isOnGround() && at.getY() < node[1] - 0.05)
			return;
		Vector dir = new Vector(node[0] + 0.5 - at.getX(), 0, node[2] + 0.5 - at.getZ());
		if(dir.lengthSquared() < 0.01)
			return;
		mob.getPathfinder().stopPathfinding();
		dir.normalize().multiply(0.2);
		mob.setVelocity(new Vector(dir.getX(), mob.getVelocity().getY(), dir.getZ()));
		mob.setRotation((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())), 0);
	}

	/** A small hop towards the goal, for a mob wedged against or on top of a barrier. */
	private void nudge(Mob mob, Location goal, long tick)
	{
		if(!mob.isOnGround())
			return;
		Location at = mob.getLocation();
		Vector dir = new Vector(goal.getX() - at.getX(), 0, goal.getZ() - at.getZ());
		if(dir.lengthSquared() < 1.0E-4)
			return;
		BoundingBox up = mob.getBoundingBox().shift(0, 1.1, 0);
		if(solid(mob.getWorld(), up) || mob.getWorld().hasCollisionsIn(up))
			return;
		dir.normalize().multiply(0.2);
		mob.setVelocity(new Vector(dir.getX(), 0.45, dir.getZ()));
		lastHop.put(mob.getUniqueId(), tick);
		debug(mob, "nudged towards its goal");
	}

	/** Where the mob wants to go: its target, or the end of its current path. */
	private static Location goalOf(Mob mob)
	{
		if(mob.getTarget() != null && mob.getTarget().isValid() && mob.getTarget().getWorld() == mob.getWorld())
			return mob.getTarget().getLocation();
		Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
		return path == null ? null : path.getFinalPoint();
	}

	/** Plans a route around barriers if the mob's own route goes through one. */
	private void considerPlanning(Mob mob, long tick)
	{
		if(planningOff(mob.getWorld()))
			return;
		Long until = failedUntil.get(mob.getUniqueId());
		if(until != null && until > tick)
			return;
		Location goal = goalOf(mob);
		if(goal == null || goal.distanceSquared(mob.getLocation()) < 2.0)
			return;
		Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
		if(path == null)
		{
			path = findPathBudgeted(mob, goal);
			if(path == null)
				return; // no search left this tick: looked at again shortly
		}
		if(!crosses(mob.getWorld(), path))
			return;
		plan(mob, goal, tick);
	}

	/** Does the route step into a barrier cell? */
	private boolean crosses(World world, Pathfinder.PathResult path)
	{
		List<Location> points = path.getPoints();
		for(int i = Math.max(0, path.getNextPointIndex() - 1); i < points.size(); i++)
		{
			Location p = points.get(i);
			if(blocked(world, p.getBlockX(), p.getBlockY(), p.getBlockZ(), null) || blocked(world, p.getBlockX(), p.getBlockY() + 1, p.getBlockZ(), null))
				return true;
			// The move from the point before: a thin barrier between two open cells blocks it
			if(i > 0)
			{
				Location q = points.get(i - 1);
				if(moveBlocked(world, q.getBlockX(), q.getBlockY(), q.getBlockZ(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), null))
					return true;
			}
		}
		return false;
	}

	/** @return false when this tick's search budget is used up (nothing changed; try again next tick) */
	private boolean plan(Mob mob, Location goal, long tick)
	{
		if(planningOff(mob.getWorld()))
			return true;
		if(budgetTick != tick)
		{
			budgetTick = tick;
			searchesLeft = SEARCHES_PER_TICK;
		}
		if(searchesLeft <= 0)
			return false;
		searchesLeft--;
		Location at = mob.getLocation();
		// Feet a little below a block's top (resting on a barrier, which holds it a hair lower) still count as that block
		int[] start = startCell(mob);
		Route route = search(mob.getWorld(), start[0], start[1], start[2],
				goal.getBlockX(), (int) Math.floor(goal.getY() + 0.01), goal.getBlockZ(), maxDrop(mob));
		if(route == null)
		{
			plans.remove(mob.getUniqueId());
			failedUntil.put(mob.getUniqueId(), tick + 40);
			return true;
		}
		Plan plan = new Plan();
		plan.nodes = route.nodes();
		plan.full = route.full();
		plan.cutShort = route.cutShort() && route.nodes().size() > 2;
		plan.index = 1;
		plan.goal = goal.clone();
		plan.madeTick = tick;
		plans.put(mob.getUniqueId(), plan);
		follow(mob, plan, tick);
		return true;
	}

	/** Walks the mob along its planned route, a few nodes ahead at a time. */
	private void follow(Mob mob, Plan plan, long tick)
	{
		Location at = mob.getLocation();
		Location goal = goalOf(mob);
		// The target moved, or the plan is old: plan again (or drop it when the way is clear now)
		if(goal == null || goal.getWorld() != at.getWorld())
		{
			plans.remove(mob.getUniqueId());
			return;
		}
		if(goal.distanceSquared(plan.goal) > 16 || tick - plan.madeTick > (plan.full ? 60 : 20))
		{
			// Is the mob's own route clear now? Then it needs no help.
			Pathfinder.PathResult own = findPathBudgeted(mob, goal);
			if(own != null && own.canReachFinalPoint() && !crosses(mob.getWorld(), own))
			{
				plans.remove(mob.getUniqueId());
				return;
			}
			// Plan again; the old plan stays in use until there is a new one (no search left: keep following meanwhile)
			if(own != null && plan(mob, goal, tick))
				return;
		}
		while(plan.index < plan.nodes.size())
		{
			int[] n = plan.nodes.get(plan.index);
			double ddx = n[0] + 0.5 - at.getX(), ddz = n[2] + 0.5 - at.getZ();
			if(ddx * ddx + ddz * ddz < 0.6 && Math.abs(n[1] - at.getY()) < 1.5)
			{
				plan.index++;
				plan.direct = false; // made it to the next step: let Minecraft try again from here
			}
			else
				break;
		}
		if(plan.index >= plan.nodes.size())
		{
			if(plan.full || plan.cutShort)
			{
				// Arrived at the end of the planned route: its own AI takes over again
				plans.remove(mob.getUniqueId());
				return;
			}
			// No way around: wait here as close as it can get, like a mob that cannot reach its target, instead of letting
			// its own AI walk (and jump) into the barrier. The plan is redone every second in case the way opens.
			mob.getPathfinder().stopPathfinding();
			return;
		}
		// The next step is a drop deeper than Minecraft's own pathfinder will take: walk it off the edge directly
		int[] next = plan.nodes.get(plan.index);
		int feetY = (int) Math.floor(at.getY() + 0.01);
		if(next[1] < feetY - 3)
		{
			mob.getPathfinder().stopPathfinding();
			Vector dir = new Vector(next[0] + 0.5 - at.getX(), 0, next[2] + 0.5 - at.getZ());
			if(dir.lengthSquared() > 1.0E-4 && mob.isOnGround())
			{
				dir.normalize().multiply(0.22);
				mob.setVelocity(new Vector(dir.getX(), mob.getVelocity().getY(), dir.getZ()));
				mob.setRotation((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())), 0);
			}
			return;
		}
		// The next step is up onto a barrier lying on the floor (a low rail players step over). Minecraft does not know it
		// is there, so it never jumps for it: being stopped by a barrier is not bumping into a block. Jump it here.
		if(next[1] > feetY && mob.isOnGround())
		{
			Vector dir = new Vector(next[0] + 0.5 - at.getX(), 0, next[2] + 0.5 - at.getZ());
			if(dir.lengthSquared() < 1.6 * 1.6 && dir.lengthSquared() > 1.0E-4)
			{
				dir.normalize().multiply(0.2);
				mob.setVelocity(new Vector(dir.getX(), 0.45, dir.getZ()));
				mob.setRotation((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())), 0);
			}
		}
		// Mid-jump up onto the next step: once its feet are above that step, carry it forward onto it. Its forward speed was
		// lost while it was still below the edge (the move into the step was blocked), so on its own it went straight up
		// and came back down where it jumped from, over and over.
		if(!mob.isOnGround() && plan.index > 0 && next[1] > plan.nodes.get(plan.index - 1)[1] && at.getY() >= next[1] - 0.05)
		{
			steerTo(mob, next);
			return;
		}
		// Aim a few nodes ahead (smoother), but never past a turn that would cut through a barrier, nor past a deep drop
		int aim = plan.index;
		for(int k = plan.index + 1; k < Math.min(plan.nodes.size(), plan.index + 4); k++)
		{
			int[] n = plan.nodes.get(k);
			if(n[1] < plan.nodes.get(k - 1)[1] - 3)
				break;
			if(store.segmentCrosses(at.getWorld().getName(), at.toVector().add(new Vector(0, 0.5, 0)), new Vector(n[0] + 0.5, n[1] + 0.5, n[2] + 0.5)))
				break;
			aim = k;
		}
		int[] n = plan.nodes.get(aim);
		// Minecraft's pathfinder cannot start from inside a block's space (a mob on a barrier piece right against iron bars
		// has its centre in the bars' block), so it then refuses to walk the mob anywhere. Steer it by hand in that case.
		if(plan.direct)
		{
			steerTo(mob, plan.nodes.get(plan.index));
			return;
		}
		// Sent again only when it aims at a new node, has lost its path, or now and then: every moveTo is a full path
		// search, and this used to run every tick for every mob on a route
		if(aim == plan.sentAim && mob.getPathfinder().hasPath() && tick - plan.sentTick < 20)
			return;
		plan.sentAim = aim;
		plan.sentTick = tick;
		if(!mob.getPathfinder().moveTo(new Location(at.getWorld(), n[0] + 0.5, n[1], n[2] + 0.5), 1.0))
		{
			plan.direct = true;
			steerTo(mob, plan.nodes.get(plan.index));
		}
	}

	/** How far the mob may drop down: its own safe fall distance (3 normally; other plugins may raise it). */
	private static int maxDrop(Mob mob)
	{
		var attr = mob.getAttribute(org.bukkit.attribute.Attribute.SAFE_FALL_DISTANCE);
		double v = attr == null ? 3 : attr.getValue();
		return (int) Math.max(3, Math.min(32, Math.floor(v)));
	}

	// ------------------------------------------------------------------ A*

	private record Node(int x, int y, int z, double g, double f, Node parent) {}

	/**
	 * A* over the blocks, with barrier cells counting as solid. A mob can stand where its feet and head cells are open
	 * (passable and not a barrier) and the block below is solid; it can step up 1 block and drop down 3.
	 *
	 * @return the route as feet positions from start to goal, or null when there is none within reach
	 */
	private Route search(World world, int sx, int sy, int sz, int gx, int gy, int gz, int maxDrop)
	{
		Map<Long, Boolean> cache = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		Map<Long, Double> best = new HashMap<>();
		Node start = new Node(sx, sy, sz, 0, h(sx, sy, sz, gx, gy, gz), null);
		open.add(start);
		best.put(key(sx, sy, sz), 0.0);
		Node closest = start;
		int visited = 0;
		while(!open.isEmpty() && visited++ < MAX_NODES)
		{
			Node cur = open.poll();
			if(Math.abs(cur.x() - gx) <= 1 && Math.abs(cur.z() - gz) <= 1 && Math.abs(cur.y() - gy) <= 1)
				return new Route(build(cur), true, false);
			if(h(cur.x(), cur.y(), cur.z(), gx, gy, gz) < h(closest.x(), closest.y(), closest.z(), gx, gy, gz))
				closest = cur;
			Double known = best.get(key(cur.x(), cur.y(), cur.z()));
			if(known != null && known < cur.g())
				continue;
			for(int[] d : DIRS)
			{
				int nx = cur.x() + d[0], nz = cur.z() + d[1];
				if(Math.abs(nx - sx) > MAX_RANGE || Math.abs(nz - sz) > MAX_RANGE)
					continue;
				boolean diagonal = d[0] != 0 && d[1] != 0;
				// No cutting corners: both sides of a diagonal step must be open at this height
				if(diagonal && (!open(world, cur.x() + d[0], cur.y(), cur.z(), cache) || !open(world, cur.x(), cur.y(), cur.z() + d[1], cache)))
					continue;
				int ny = Integer.MIN_VALUE;
				if(standable(world, nx, cur.y(), nz, cache))
					ny = cur.y();
				else if(!diagonal && standable(world, nx, cur.y() + 1, nz, cache) && open(world, cur.x(), cur.y() + 2, cur.z(), cache))
					ny = cur.y() + 1;
				else if(!diagonal)
					for(int drop = 1; drop <= maxDrop; drop++)
					{
						if(!open(world, nx, cur.y() - drop + 1, nz, cache))
							break;
						if(standable(world, nx, cur.y() - drop, nz, cache))
						{
							ny = cur.y() - drop;
							break;
						}
					}
				if(ny == Integer.MIN_VALUE)
					continue;
				if(moveBlocked(world, cur.x(), cur.y(), cur.z(), nx, ny, nz, cache))
					continue;
				double step = (diagonal ? 1.414 : 1.0) + (ny > cur.y() ? 0.4 : ny < cur.y() ? 0.2 * (cur.y() - ny) : 0);
				double g = cur.g() + step;
				long k = key(nx, ny, nz);
				Double old = best.get(k);
				if(old != null && old <= g)
					continue;
				best.put(k, g);
				open.add(new Node(nx, ny, nz, g, g + h(nx, ny, nz, gx, gy, gz), cur));
			}
		}
		// No way around (or the search limit was hit first): as close to the goal as it can get (possibly right where it is)
		return new Route(build(closest), false, !open.isEmpty());
	}

	private static double h(int x, int y, int z, int gx, int gy, int gz)
	{
		int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
		return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(y - gy);
	}

	private static List<int[]> build(Node end)
	{
		List<int[]> out = new ArrayList<>();
		for(Node n = end; n != null; n = n.parent())
			out.add(new int[] {n.x(), n.y(), n.z()});
		Collections.reverse(out);
		return out;
	}

	/** Feet and head open, solid block below. */
	private boolean standable(World world, int x, int y, int z, Map<Long, Boolean> cache)
	{
		if(!open(world, x, y, z, cache) || !open(world, x, y + 1, z, cache))
			return false;
		if(!world.isChunkLoaded(x >> 4, z >> 4))
			return false;
		// Real ground, or a barrier cell holding a real block (bars in a floor). An empty barrier cell counts only when it
		// lies straight on a real block (a walkway of blockers over bars): players walk on that too.
		Block below = world.getBlockAt(x, y - 1, z);
		if(!below.isPassable())
			return true;
		// A barrier under its feet holds it up like a block would (the top part of the cell below, under a mob-sized spot)
		return store.intersects(world, new BoundingBox(x + 0.2, y - 0.3, z + 0.2, x + 0.8, y - 0.01, z + 0.8));
	}

	/** A cell a mob can be in: passable, and not part of a barrier. */
	private boolean open(World world, int x, int y, int z, Map<Long, Boolean> cache)
	{
		if(!world.isChunkLoaded(x >> 4, z >> 4))
			return false;
		Block block = world.getBlockAt(x, y, z);
		return block.isPassable() && !blocked(world, x, y, z, cache);
	}

	/**
	 * Does stepping from one cell to the next pass through a barrier? Checked along the middle of the move at foot and head
	 * height (and, stepping up or dropping, at the higher of the two), with the barriers' exact shapes, so a thin piece
	 * between two cells counts. Only cells near a barrier get the exact check.
	 */
	private boolean moveBlocked(World world, int x, int y, int z, int nx, int ny, int nz, Map<Long, Boolean> cache)
	{
		if(!near(world, x, y, z, cache) && !near(world, nx, ny, nz, cache))
			return false;
		int top = Math.max(y, ny);
		double[] heights = {y + 0.3, y + 1.2, top + 0.3, top + 1.2};
		for(double h : heights)
			if(store.segmentHits(world, new Vector(x + 0.5, h, z + 0.5), new Vector(nx + 0.5, h, nz + 0.5)))
				return true;
		return false;
	}

	/** Is a barrier anywhere close to this cell (its foot and head spaces, one block around)? */
	private boolean near(World world, int x, int y, int z, Map<Long, Boolean> cache)
	{
		long k = key(x, y, z) ^ 0x4000000000000000L;
		if(cache != null)
		{
			Boolean known = cache.get(k);
			if(known != null)
				return known;
		}
		boolean b = store.near(world, new BoundingBox(x - 0.5, y, z - 0.5, x + 1.5, y + 2, z + 1.5));
		if(cache != null)
			cache.put(k, b);
		return b;
	}

	/** Is the middle of this cell inside a barrier? */
	private boolean blocked(World world, int x, int y, int z, Map<Long, Boolean> cache)
	{
		long k = key(x, y, z);
		if(cache != null)
		{
			Boolean known = cache.get(k);
			if(known != null)
				return known;
		}
		boolean b = store.intersects(world, new BoundingBox(x + 0.2, y + 0.05, z + 0.2, x + 0.8, y + 0.95, z + 0.8));
		if(cache != null)
			cache.put(k, b);
		return b;
	}

	private static long key(int x, int y, int z)
	{
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}
}
