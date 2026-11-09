package dev.blockblocker;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Keeps players out of barriers purely by adjusting their movement. Nothing is ever placed, so blocks, items and entities
 * are never touched.
 */
public class BarrierListener implements Listener
{
	public static final String BYPASS = "blockblocker.bypass";

	private final BarrierStore store;

	public BarrierListener(BarrierStore store)
	{
		this.store = store;
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event)
	{
		if(!event.hasChangedPosition())
			return;

		Location from = event.getFrom();
		Location to = event.getTo();
		if(from.getWorld() == null || !from.getWorld().equals(to.getWorld()))
			return;

		World w = from.getWorld();
		String world = w.getName();
		if(!store.hasBarriersIn(world))
			return;

		Player player = event.getPlayer();
		if(player.hasPermission(BYPASS))
			return;

		double half = player.getWidth() / 2.0;
		double height = player.getHeight();
		double[] pos = {from.getX(), from.getY(), from.getZ()};
		double[] target = {to.getX(), to.getY(), to.getZ()};

		// Already inside a barrier (it was created around them, or they were pushed in): let them walk out freely.
		if(store.intersects(w,box(pos, half, height)))
			return;

		// The player's client resolves its own collisions Y first, then the larger horizontal axis first. A move the client
		// made legally can look blocked if the server tries the axes in a different order (this is what caused rubber-banding
		// around corners). So the move is accepted untouched if EITHER horizontal order reaches the target.
		int[][] orders = {{1, 0, 2}, {1, 2, 0}};
		double[] best = null;
		double bestMissed = Double.MAX_VALUE;
		for(int[] order : orders)
		{
			double[] reached = walk(w, half, height, pos, target, order);
			double missed = 0;
			for(int i = 0; i < 3; i++)
				missed += Math.abs(target[i] - reached[i]);
			if(missed == 0)
				return; // fully legal move: leave it alone
			if(missed < bestMissed)
			{
				bestMissed = missed;
				best = reached;
			}
		}

		// Genuinely blocked either way: slide as far as possible along the wall.
		Location fixed = to.clone();
		fixed.setX(best[0]);
		fixed.setY(best[1]);
		fixed.setZ(best[2]);
		event.setTo(fixed);
	}

	/** Moves from start toward target one axis at a time (in the given order), stopping flush against any barrier. */
	private double[] walk(World w, double half, double height, double[] start, double[] target, int[] order)
	{
		double[] pos = start.clone();
		for(int axis : order)
		{
			double delta = target[axis] - pos[axis];
			if(delta == 0)
				continue;

			double[] probe = pos.clone();
			probe[axis] = target[axis];
			// Swept box: covers everything crossed this tick, so fast movement (elytra, riptide, lag) can't tunnel through.
			if(!store.intersects(w, box(pos, half, height).union(box(probe, half, height))))
			{
				pos[axis] = target[axis];
				continue;
			}

			// Blocked: slide as close to the barrier face as possible.
			double lo = 0, hi = 1;
			for(int i = 0; i < 8; i++)
			{
				double mid = (lo + hi) / 2;
				probe[axis] = pos[axis] + delta * mid;
				if(store.intersects(w, box(pos, half, height).union(box(probe, half, height))))
					hi = mid;
				else
					lo = mid;
			}
			pos[axis] += delta * lo;
		}
		return pos;
	}

	/** Ender pearls and chorus fruit can't be used to hop over or into a barrier. Admin /tp and plugin teleports are left alone. */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event)
	{
		// By name: Paper is renaming CHORUS_FRUIT (-> CONSUMABLE_EFFECT), so match either without a removed-constant link error.
		String cause = event.getCause().name();
		if(!cause.equals("ENDER_PEARL") && !cause.equals("CHORUS_FRUIT") && !cause.equals("CONSUMABLE_EFFECT"))
			return;

		Location from = event.getFrom();
		Location to = event.getTo();
		if(from.getWorld() == null || !from.getWorld().equals(to.getWorld()))
			return;

		World w = from.getWorld();
		String world = w.getName();
		Player player = event.getPlayer();
		if(!store.hasBarriersIn(world) || player.hasPermission(BYPASS))
			return;

		double half = player.getWidth() / 2.0;
		double height = player.getHeight();
		double[] dest = {to.getX(), to.getY(), to.getZ()};
		Vector a = new Vector(from.getX(), from.getY() + height / 2, from.getZ());
		Vector b = new Vector(to.getX(), to.getY() + height / 2, to.getZ());

		if(store.intersects(w,box(dest, half, height)) || store.segmentCrosses(world, a, b))
			event.setCancelled(true);
	}

	private static BoundingBox box(double[] p, double half, double height)
	{
		return new BoundingBox(p[0] - half, p[1], p[2] - half, p[0] + half, p[1] + height, p[2] + half);
	}
}
