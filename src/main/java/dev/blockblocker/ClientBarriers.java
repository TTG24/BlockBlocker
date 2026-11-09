package dev.blockblocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Makes each nearby player's own client collide with the wall (no rubber-banding). Everything here is packets only: the
 * real world is never modified.
 * <ul>
 * <li>Air cells get a fake barrier block, so no existing block is ever hidden.</li>
 * <li>Cells holding a non-full block (fence, slab, grass, water...) optionally get an invisible client-side shulker
 * instead, so the block stays visible but the client still treats the whole cell as solid.</li>
 * </ul>
 * The server-side check in {@link BarrierListener} stays on as a backup.
 */
public class ClientBarriers implements Listener
{
	private static final int RADIUS = 8;
	private static final int RESEND_EVERY = 10; // runs; lets fake blocks survive chunk resends
	private static final int RESEND_COLLIDERS_EVERY = 50; // runs

	private record Pos(int x, int y, int z)
	{
	}

	/** What a player should currently be shown. */
	private record Wanted(Set<Pos> air, Set<Pos> colliders)
	{
	}

	private static final class State
	{
		String world = "";
		Set<Pos> sent = new HashSet<>();
		Map<Pos, Integer> colliders = new HashMap<>();
	}

	private final JavaPlugin plugin;
	private final BarrierStore store;
	private final boolean useColliders;
	private final Map<UUID, State> states = new HashMap<>();
	private BlockData barrier;
	private int counter;

	public ClientBarriers(JavaPlugin plugin, BarrierStore store, boolean useColliders)
	{
		this.plugin = plugin;
		this.store = store;
		this.useColliders = useColliders;
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
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event)
	{
		states.remove(event.getPlayer().getUniqueId());
	}

	private void tick()
	{
		boolean enabled = plugin.getConfig().getBoolean("client-blocks", true);
		boolean resend = ++counter % RESEND_EVERY == 0;
		boolean resendColliders = counter % RESEND_COLLIDERS_EVERY == 0;

		for(Player p : Bukkit.getOnlinePlayers())
		{
			State st = states.get(p.getUniqueId());
			Wanted wanted = enabled ? wanted(p) : new Wanted(Set.of(), Set.of());
			if(st == null)
			{
				if(wanted.air().isEmpty() && wanted.colliders().isEmpty())
					continue;
				st = new State();
				states.put(p.getUniqueId(), st);
			}

			String world = p.getWorld().getName();
			if(!world.equals(st.world))
			{
				// New world: the client already dropped everything.
				st.sent.clear();
				st.colliders.clear();
				st.world = world;
			}

			// Fake barrier blocks (air cells).
			for(Pos old : st.sent)
				if(!wanted.air().contains(old))
					restore(p, old);
			for(Pos pos : wanted.air())
				if(resend || !st.sent.contains(pos))
					p.sendBlockChange(new Location(p.getWorld(), pos.x(), pos.y(), pos.z()), barrier);
			st.sent = new HashSet<>(wanted.air());

			// Invisible shulker colliders (non-full blocks).
			if(useColliders)
				syncColliders(p, st, wanted.colliders(), resendColliders);

			if(st.sent.isEmpty() && st.colliders.isEmpty())
				states.remove(p.getUniqueId());
		}
	}

	private void syncColliders(Player p, State st, Set<Pos> wanted, boolean resend)
	{
		var gone = new java.util.ArrayList<Integer>();
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

		for(Pos pos : wanted)
		{
			Integer id = st.colliders.get(pos);
			if(id == null)
			{
				id = ColliderPackets.newEntityId();
				st.colliders.put(pos, id);
				ColliderPackets.spawn(p, id, pos.x(), pos.y(), pos.z());
			}
			else if(resend)
				ColliderPackets.spawn(p, id, pos.x(), pos.y(), pos.z()); // same id: replaces it if the client lost it
		}
	}

	private void destroyColliders(Player p, Iterable<Integer> ids)
	{
		if(!useColliders)
			return;
		var list = new java.util.ArrayList<Integer>();
		ids.forEach(list::add);
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
		Set<Pos> air = new HashSet<>();
		Set<Pos> colliders = new HashSet<>();
		World w = p.getWorld();
		if(!store.hasBarriersIn(w.getName()) || p.hasPermission(BarrierListener.BYPASS))
			return new Wanted(air, colliders);

		Location l = p.getLocation();
		int px = l.getBlockX(), py = l.getBlockY(), pz = l.getBlockZ();
		BoundingBox body = p.getBoundingBox();

		for(Barrier b : store.barriersIn(w.getName()))
		{
			// Already inside: leave them alone so they can walk out.
			if(b.overlaps(body))
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
						if(block.getType().isAir())
							air.add(new Pos(x, y, z));
						else if(useColliders && !BarrierStore.isFullCube(block) && !new BoundingBox(x, y, z, x + 1, y + 1, z + 1).overlaps(body))
							colliders.add(new Pos(x, y, z));
					}
				}
		}
		return new Wanted(air, colliders);
	}
}
