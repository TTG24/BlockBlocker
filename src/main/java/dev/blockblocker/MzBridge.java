package dev.blockblocker;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the barrier cells of MinecraftZombies games by reflection (no compile-time dependency), so they can get the same
 * client-side collision as BlockBlocker barriers. MZ keeps enforcing the barriers on the server by itself; this only lets
 * players' own clients collide with them too, which removes the "pulled back" rubber-banding once a barrier is broken.
 *
 * MZ blocks players from a barrier's cells at every stage (even fully broken), and only players who are in a game, so
 * cells are only returned for players in a game, and only while they are in that game's world. Main thread only.
 */
final class MzBridge
{
	private static final long REFRESH_MS = 1000;

	/**
	 * Name given to the single-cell barriers made from MZ's barrier blocks. MZ itself blocks players from the WHOLE cell
	 * whatever block is in it, so the client must be stopped at the cell edge too (no thin-block exemption for these).
	 */
	static final String CELL_NAME = "mz";

	private record Cells(String world, List<Barrier> cells)
	{
	}

	private final Logger log;
	private final Object gameManager;
	private final Method getGames, getGameOfPlayer, isPlayerInGame, getWorld, getBarriers, getBlocks;
	private final Field barrierManager;

	private Map<Object, Cells> byGame = new IdentityHashMap<>();
	private Set<String> gameWorlds = Set.of();
	private long refreshedAt;
	private boolean broken;

	private MzBridge(Logger log, Object gameManager, Method getGames, Method getGameOfPlayer, Method isPlayerInGame, Method getWorld,
	                 Field barrierManager, Method getBarriers, Method getBlocks)
	{
		this.log = log;
		this.gameManager = gameManager;
		this.getGames = getGames;
		this.getGameOfPlayer = getGameOfPlayer;
		this.isPlayerInGame = isPlayerInGame;
		this.getWorld = getWorld;
		this.barrierManager = barrierManager;
		this.getBarriers = getBarriers;
		this.getBlocks = getBlocks;
	}

	/** @return a bridge, or null if MinecraftZombies isn't installed or its internals aren't what we expect. */
	static MzBridge create(Logger log)
	{
		Plugin mz = Bukkit.getPluginManager().getPlugin("MinecraftZombies");
		if(mz == null || !mz.isEnabled())
		{
			log.warning("mz-barriers is on but the MinecraftZombies plugin is not installed/enabled; ignoring it.");
			return null;
		}
		try
		{
			ClassLoader cl = mz.getClass().getClassLoader();
			Class<?> gameManagerClass = Class.forName("com.theprogrammingturkey.comz.game.GameManager", true, cl);
			Class<?> gameClass = Class.forName("com.theprogrammingturkey.comz.game.Game", true, cl);
			Class<?> barrierManagerClass = Class.forName("com.theprogrammingturkey.comz.game.managers.BarrierManager", true, cl);
			Class<?> barrierClass = Class.forName("com.theprogrammingturkey.comz.game.features.Barrier", true, cl);
			return new MzBridge(log,
					gameManagerClass.getField("INSTANCE").get(null),
					gameManagerClass.getMethod("getGames"),
					gameManagerClass.getMethod("getGame", Player.class),
					gameManagerClass.getMethod("isPlayerInGame", Player.class),
					gameClass.getMethod("getWorld"),
					gameClass.getField("barrierManager"),
					barrierManagerClass.getMethod("getBarriers"),
					barrierClass.getMethod("getBlocks"));
		}
		catch(Throwable t)
		{
			log.log(Level.WARNING, "mz-barriers: could not read MinecraftZombies' barriers (a different MZ version?); ignoring it.", t);
			return null;
		}
	}

	/** The barrier cells this player should collide with right now (empty if they aren't in an MZ game). */
	List<Barrier> barriersFor(Player player)
	{
		if(broken)
			return List.of();
		try
		{
			refreshIfStale();
			String world = player.getWorld().getName();
			if(!gameWorlds.contains(world) || !(Boolean) isPlayerInGame.invoke(gameManager, player))
				return List.of();
			Object game = getGameOfPlayer.invoke(gameManager, player);
			Cells cells = game == null ? null : byGame.get(game);
			return cells != null && cells.world().equals(world) ? cells.cells() : List.of();
		}
		catch(Throwable t)
		{
			broken = true;
			log.log(Level.WARNING, "mz-barriers: stopped, because reading MinecraftZombies failed.", t);
			return List.of();
		}
	}

	/** Rebuilds the cell lists about once a second: barriers only change while an arena is being set up. */
	private void refreshIfStale() throws ReflectiveOperationException
	{
		long now = System.currentTimeMillis();
		if(now - refreshedAt < REFRESH_MS)
			return;
		refreshedAt = now;

		Map<Object, Cells> fresh = new IdentityHashMap<>();
		Set<String> worlds = new HashSet<>();
		for(Object game : (List<?>) getGames.invoke(gameManager))
		{
			World world = (World) getWorld.invoke(game);
			Object manager = barrierManager.get(game);
			if(world == null || manager == null)
				continue;
			List<Barrier> cells = new ArrayList<>();
			for(Object barrier : (List<?>) getBarriers.invoke(manager))
				for(Object block : (List<?>) getBlocks.invoke(barrier))
				{
					Block b = (Block) block;
					cells.add(new Barrier(CELL_NAME, world.getName(), b.getX(), b.getY(), b.getZ(), b.getX(), b.getY(), b.getZ()));
				}
			if(cells.isEmpty())
				continue;
			fresh.put(game, new Cells(world.getName(), cells));
			worlds.add(world.getName());
		}
		byGame = fresh;
		gameWorlds = worlds;
	}
}
