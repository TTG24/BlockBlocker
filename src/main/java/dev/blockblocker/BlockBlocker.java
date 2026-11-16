package dev.blockblocker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class BlockBlocker extends JavaPlugin implements TabExecutor, Listener
{
	/** config "grim-ignore-near-shulkers", for the Grim hook (read on Grim's thread, so not from the config). */
	private volatile boolean grimIgnoreNear = true;

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final String PERM = "blockblocker.admin";

	private BarrierStore store;
	private ClientBarriers clientBarriers;
	private CustomEditor customEditor;

	private boolean collidersWanted;
	private boolean respectPartial;
	/** Reads MinecraftZombies' barriers when "mz-barriers" is on (null = off). */
	private MzBridge mzBridge;

	/** (Re)applies the "mz-barriers" config toggle. Safe to call again, e.g. from /block reload. */
	private void refreshMzBridge()
	{
		if(!getConfig().getBoolean("mz-barriers", false))
		{
			mzBridge = null;
			return;
		}
		if(mzBridge == null)
		{
			mzBridge = MzBridge.create(getLogger());
			if(mzBridge != null)
				getLogger().info("mz-barriers: ON. MinecraftZombies barriers now get client-side collision for players in a game.");
		}
	}

	@Override
	public void onLoad()
	{
		saveDefaultConfig();
		mergeDefaultConfig();
		// Experimental: needs a restart (not /block reload) to change, because PacketEvents starts with the server.
		collidersWanted = getConfig().getBoolean("shulker-colliders", false) && ColliderPackets.load(this);
	}

	/** Stops mobs (zombies) walking into barriers and steers them around (see MobBlocker). Nothing is placed. */
	private MobBlocker mobBlocker;

	@Override
	public void onDisable()
	{
		if(mobBlocker != null)
			mobBlocker.stop();
		if(customEditor != null)
		{
			customEditor.flushSave();
			customEditor.clearLines();
		}
		for(LineView v : showLines.values())
			v.clear();
		showLines.clear();
		if(clientBarriers != null)
			clientBarriers.shutdown();
		if(collidersWanted)
			ColliderPackets.terminate();
	}

	@Override
	public void onEnable()
	{
		getDataFolder().mkdirs();
		store = new BarrierStore(new File(getDataFolder(), "data.yml"));
		store.load();

		boolean colliders = collidersWanted && ColliderPackets.init(this);
		collidersWanted = colliders;
		// With shulker colliders the client collides with the whole cell, so the server must treat it as a whole cell too.
		respectPartial = !colliders && getConfig().getBoolean("respect-partial-blocks", true);
		store.setRespectPartial(respectPartial);
		store.setThinBlocks(getConfig().getStringList("thin-blocks"));
		store.setEnforceShapes(!"push".equalsIgnoreCase(getConfig().getString("shape-mode", "block")));
		store.setFillCell(getConfig().getBoolean("push-fill-cell", false));
		store.setBlockFill(getConfig().getBoolean("block-fill-cell", false));
		store.setConnectShapes(getConfig().getBoolean("connect-shapes", true));
		store.setCopyPartial(getConfig().getBoolean("shape-copy-partial-blocks", true));
		loadPickItem();
		getServer().getPluginManager().registerEvents(new BarrierListener(store), this);
		getServer().getPluginManager().registerEvents(this, this);
		clientBarriers = new ClientBarriers(this, store, colliders, () -> mzBridge);
		clientBarriers.start();
		getServer().getScheduler().runTaskTimer(this, this::refreshPickHolders, 5L, 5L);
		if(colliders && getServer().getPluginManager().isPluginEnabled("GrimAC") && getConfig().getBoolean("grim-ignore-near-shulkers", true))
		{
			try
			{
				ClientBarriers cb = clientBarriers;
				grimIgnoreNear = getConfig().getBoolean("grim-ignore-near-shulkers", true);
				// (Grim calls this on its packet thread: it reads a field set on the main thread, not the config itself)
				GrimHook.register(this, id -> grimIgnoreNear && cb.isNearCollider(id));
				getLogger().info("Hooked GrimAC: its movement flags are ignored right next to BlockBlocker's invisible shulkers.");
			}
			catch(Throwable t)
			{
				getLogger().warning("Could not hook GrimAC: " + t);
			}
		}
		customEditor = new CustomEditor(this, store, this::saveStore, () -> clientBarriers);
		customEditor.start();
		mobBlocker = new MobBlocker(this, store);
		mobBlocker.start();
		// Next tick, once every plugin (including MinecraftZombies) has finished loading.
		getServer().getScheduler().runTask(this, this::refreshMzBridge);
		// A click on a fake shulker never reaches Bukkit (the server has no such entity), so clicks are read from the packet:
		// for "/block pick", and to pass the click through to the real block (e.g. a sign) behind the barrier.
		if(colliders)
			ColliderPackets.listenForClicks(new ColliderPackets.ClickHooks()
			{
				@Override
				public boolean isCollider(int entityId)
				{
					return clientBarriers.isCollider(entityId);
				}

				@Override
				public boolean consumePick(Player player)
				{
					return consumePickFromPacket(player);
				}

				@Override
				public void colliderClicked(Player player, int entityId)
				{
					if(customEditor != null && customEditor.isEditing(player.getUniqueId()))
					{
						customEditor.noteEntityClick(player);
						Bukkit.getScheduler().runTask(BlockBlocker.this, () -> customEditor.clickEntity(player, true, entityId));
						return;
					}
					if(clientBarriers.isActive(player.getUniqueId()))
						Bukkit.getScheduler().runTask(BlockBlocker.this, () -> relayClick(player));
				}

				@Override
				public void attackPacket(Player player, boolean ours, int entityId)
				{
					if(ours && customEditor != null && customEditor.isEditing(player.getUniqueId()))
					{
						customEditor.noteEntityClick(player);
						Bukkit.getScheduler().runTask(BlockBlocker.this, () -> customEditor.clickEntity(player, false, entityId));
						return;
					}
					// A hit on a real entity: vanilla handles it, so the swing that follows must not be relayed again.
					if(!ours)
						lastRealAttack.put(player.getUniqueId(), System.currentTimeMillis());
					dbgAsync(player, ours ? "attack packet: your click hit one of MY invisible shulkers (no real creature)." : "attack packet: your click hit a real entity (normal vanilla attack).");
				}

				@Override
				public void swingPacket(Player player)
				{
					// Swings are handled by Bukkit's PlayerAnimationEvent (onSwing), which is reliable on every version.
				}

				@Override
				public void blockClicked(Player player, int x, int y, int z)
				{
					if(clientBarriers.isActive(player.getUniqueId()))
						Bukkit.getScheduler().runTask(BlockBlocker.this, () -> {
							if(isBarrierAirCell(player, x, y, z))
								relayClick(player);
						});
				}
			});
		// Outlines are client-side particles sent only to players who toggled /block show; nothing exists in the world.
		getServer().getScheduler().runTaskTimer(this, this::drawOutlines, 10L, 10L);
		var cmd = getCommand("block");
		if(cmd != null)
		{
			cmd.setExecutor(this);
			cmd.setTabCompleter(this);
		}

		if(!hasWorldEdit())
			getLogger().warning("Neither FastAsyncWorldEdit nor WorldEdit is installed: /block add needs a selection from one of them.");
		getLogger().info("Loaded " + store.all().size() + " barrier(s)." + (colliders ? " Shulker colliders: ON (experimental)." : ""));
	}

	/**
	 * Public API for other plugins (called by reflection, so they need no compile dependency): copies the saved barriers of
	 * one world into another as temporary, unsaved barriers. Used for arena instance worlds, whose blocks are copied from a
	 * template at the same coordinates. They disappear when the target world unloads. Main thread only.
	 *
	 * @return how many barriers were copied
	 */
	public int copyBarriers(String fromWorld, String toWorld)
	{
		int n = store.copyWorld(fromWorld, toWorld);
		if(n > 0)
			getLogger().info("Copied " + n + " barrier(s) from " + fromWorld + " to " + toWorld + ".");
		return n;
	}

	/**
	 * Public API for other plugins (called by reflection): is the middle of this block cell part of a switched-on barrier?
	 * Lets a route planner treat barriers as solid. Main thread only.
	 */
	/** For other plugins: does a straight move from a to b go through a barrier (exactly, thin pieces included)? */
	public boolean crossesBarrier(org.bukkit.World world, double ax, double ay, double az, double bx, double by, double bz)
	{
		return store.hasBarriersIn(world.getName())
				&& store.segmentHits(world, new org.bukkit.util.Vector(ax, ay, az), new org.bukkit.util.Vector(bx, by, bz));
	}

	public boolean isBarrierCell(org.bukkit.World world, int x, int y, int z)
	{
		return store.hasBarriersIn(world.getName())
				&& store.intersects(world, new org.bukkit.util.BoundingBox(x + 0.2, y + 0.05, z + 0.2, x + 0.8, y + 0.95, z + 0.8));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWorldUnload(WorldUnloadEvent event)
	{
		store.clearTemporary(event.getWorld().getName());
	}

	private boolean hasWorldEdit()
	{
		return Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit") || Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
	}

	private static final double SHOW_RANGE = 48;
	/** Players seeing outlines, with the barrier names they chose (empty = all barriers). */
	private final Map<UUID, Set<String>> viewers = new HashMap<>();

	/**
	 * /block show: all outlines on/off. /block show <name> [name...]: only those barriers (running the same names again
	 * hides them; other names switch to those).
	 */
	private void toggleShow(CommandSender sender, String[] args)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		Set<String> names = new java.util.LinkedHashSet<>();
		for(int i = 1; i < args.length; i++)
		{
			Barrier b = store.find(player.getWorld().getName(), args[i]);
			if(b == null)
			{
				msg(sender, "No barrier named '" + args[i] + "' in this world. Use /block list.", NamedTextColor.RED);
				return;
			}
			names.add(b.name());
		}
		Set<String> current = viewers.get(player.getUniqueId());
		if(current != null && (names.isEmpty() || current.equals(names)))
		{
			viewers.remove(player.getUniqueId());
			clearShowLines(player.getUniqueId());
			msg(sender, "Barrier outlines hidden.", NamedTextColor.YELLOW);
			return;
		}
		viewers.put(player.getUniqueId(), names);
		if(names.isEmpty())
			msg(sender, "Showing all barrier outlines (red) within " + (int) SHOW_RANGE + " blocks. Run /block show again to hide.", NamedTextColor.GREEN);
		else
			msg(sender, "Showing only " + String.join(", ", names) + " (red, within " + (int) SHOW_RANGE + " blocks). Run /block show to hide.", NamedTextColor.GREEN);
	}

	/** The solid outline lines each /block show viewer sees (block displays only they can see, like the custom editor's). */
	private final Map<UUID, LineView> showLines = new HashMap<>();
	/** Most outline lines one player is shown at once (each is an entity on their client). */
	private static final int SHOW_MAX_LINES = 800;

	private void clearShowLines(UUID id)
	{
		LineView v = showLines.remove(id);
		if(v != null)
			v.clear();
	}

	private void drawOutlines()
	{
		if(viewers.isEmpty() && showLines.isEmpty())
			return;
		long t0 = System.nanoTime();
		try
		{
			drawOutlinesInner();
		}
		finally
		{
			Perf.add(Perf.OUTLINES, t0);
		}
	}

	private void drawOutlinesInner()
	{
		viewers.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
		// Lines of players who stopped viewing (or left) are removed
		for(UUID id : new ArrayList<>(showLines.keySet()))
			if(!viewers.containsKey(id))
				clearShowLines(id);
		for(Map.Entry<UUID, Set<String>> view : viewers.entrySet())
		{
			Player p = Bukkit.getPlayer(view.getKey());
			if(p == null)
				continue;
			Set<String> only = view.getValue();
			Location loc = p.getLocation();
			Set<LineView.Line> lines = new java.util.LinkedHashSet<>();
			for(Barrier b : store.barriersIn(p.getWorld().getName()))
			{
				if(lines.size() >= SHOW_MAX_LINES)
					break;
				if(!only.isEmpty() && !only.contains(b.name()))
					continue;
				double x0 = b.minX(), y0 = b.minY(), z0 = b.minZ(), x1 = b.maxX() + 1.0, y1 = b.maxY() + 1.0, z1 = b.maxZ() + 1.0;
				// Skip barriers entirely out of range.
				if(loc.getX() < x0 - SHOW_RANGE || loc.getX() > x1 + SHOW_RANGE || loc.getZ() < z0 - SHOW_RANGE || loc.getZ() > z1 + SHOW_RANGE
						|| loc.getY() < y0 - SHOW_RANGE || loc.getY() > y1 + SHOW_RANGE)
					continue;
				if(b.footprint() != null)
					footprintOutline(lines, loc, b);
				else if(b.volume() != null)
					volumeOutline(lines, loc, b);
				else
					LineView.box(lines, new double[] {x0, y0, z0, x1, y1, z1}, LineView.Style.SHOW);
			}
			showLines.computeIfAbsent(p.getUniqueId(), k -> new LineView(this)).show(p, lines);
		}
	}

	/** Outlines a shaped barrier: its outer wall edges at the bottom and top, plus a vertical line at each wall corner. */
	private void footprintOutline(Set<LineView.Line> out, Location l, Barrier b)
	{
		Footprint fp = b.footprint();
		int px = l.getBlockX(), pz = l.getBlockZ(), r = (int) SHOW_RANGE;
		double y0 = b.minY(), y1 = b.maxY() + 1.0;
		for(int x = Math.max(b.minX(), px - r); x <= Math.min(b.maxX(), px + r); x++)
			for(int z = Math.max(b.minZ(), pz - r); z <= Math.min(b.maxZ(), pz + r); z++)
			{
				if(out.size() >= SHOW_MAX_LINES)
					return;
				if(!fp.contains(x, z))
					continue;
				if(!fp.contains(x, z - 1)) // wall on the north side
					wallEdge(out, x, z, x + 1, z, y0, y1);
				if(!fp.contains(x, z + 1)) // south
					wallEdge(out, x, z + 1, x + 1, z + 1, y0, y1);
				if(!fp.contains(x - 1, z)) // west
					wallEdge(out, x, z, x, z + 1, y0, y1);
				if(!fp.contains(x + 1, z)) // east
					wallEdge(out, x + 1, z, x + 1, z + 1, y0, y1);
			}
	}

	/** Free-form shapes: the edges of every exposed cell face near the viewer (capped so it can't flood the client). */
	private void volumeOutline(Set<LineView.Line> out, Location l, Barrier b)
	{
		final int r = 24;
		int px = l.getBlockX(), py = l.getBlockY(), pz = l.getBlockZ();
		for(int x = Math.max(b.minX(), px - r); x <= Math.min(b.maxX(), px + r); x++)
			for(int z = Math.max(b.minZ(), pz - r); z <= Math.min(b.maxZ(), pz + r); z++)
				for(int y = Math.max(b.minY(), py - r); y <= Math.min(b.maxY(), py + r); y++)
				{
					if(out.size() >= SHOW_MAX_LINES)
						return;
					if(!b.contains(x, y, z))
						continue;
					if(!b.contains(x + 1, y, z))
						face(out, x + 1, y, z, x + 1, y + 1, z + 1);
					if(!b.contains(x - 1, y, z))
						face(out, x, y, z, x, y + 1, z + 1);
					if(!b.contains(x, y + 1, z))
						face(out, x, y + 1, z, x + 1, y + 1, z + 1);
					if(!b.contains(x, y - 1, z))
						face(out, x, y, z, x + 1, y, z + 1);
					if(!b.contains(x, y, z + 1))
						face(out, x, y, z + 1, x + 1, y + 1, z + 1);
					if(!b.contains(x, y, z - 1))
						face(out, x, y, z, x + 1, y + 1, z);
				}
	}

	/** The 4 edges of a flat, axis-aligned square (shared edges are only added once). */
	private static void face(Set<LineView.Line> out, double x0, double y0, double z0, double x1, double y1, double z1)
	{
		var s = LineView.Style.SHOW;
		if(x0 == x1) // flat on x
		{
			out.add(LineView.Line.of(x0, y0, z0, x0, y1, z0, s));
			out.add(LineView.Line.of(x0, y0, z1, x0, y1, z1, s));
			out.add(LineView.Line.of(x0, y0, z0, x0, y0, z1, s));
			out.add(LineView.Line.of(x0, y1, z0, x0, y1, z1, s));
		}
		else if(y0 == y1) // flat on y
		{
			out.add(LineView.Line.of(x0, y0, z0, x1, y0, z0, s));
			out.add(LineView.Line.of(x0, y0, z1, x1, y0, z1, s));
			out.add(LineView.Line.of(x0, y0, z0, x0, y0, z1, s));
			out.add(LineView.Line.of(x1, y0, z0, x1, y0, z1, s));
		}
		else // flat on z
		{
			out.add(LineView.Line.of(x0, y0, z0, x1, y0, z0, s));
			out.add(LineView.Line.of(x0, y1, z0, x1, y1, z0, s));
			out.add(LineView.Line.of(x0, y0, z0, x0, y1, z0, s));
			out.add(LineView.Line.of(x1, y0, z0, x1, y1, z0, s));
		}
	}

	private static void wallEdge(Set<LineView.Line> out, double xa, double za, double xb, double zb, double y0, double y1)
	{
		out.add(LineView.Line.of(xa, y0, za, xb, y0, zb, LineView.Style.SHOW));
		out.add(LineView.Line.of(xa, y1, za, xb, y1, zb, LineView.Style.SHOW));
		out.add(LineView.Line.of(xa, y0, za, xa, y1, za, LineView.Style.SHOW));
	}

	private void saveStore()
	{
		try
		{
			store.save();
		}
		catch(IOException e)
		{
			getLogger().log(Level.SEVERE, "Could not save data.yml", e);
		}
	}

	private static void msg(CommandSender to, String text, NamedTextColor color)
	{
		to.sendMessage(Component.text("[BlockBlocker] ", NamedTextColor.DARK_GRAY).append(Component.text(text, color)));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
	{
		if(!sender.hasPermission(PERM))
		{
			msg(sender, "You don't have permission.", NamedTextColor.RED);
			return true;
		}

		String sub = args.length == 0 ? "add" : canonical(args[0]);
		switch(sub)
		{
			case "add" -> add(sender, args, false);
			case "addshape" -> add(sender, args, true);
			case "shapes" -> shapes(sender, args);
			case "connect" -> connect(sender, args);
			case "remove" -> remove(sender, args);
			case "edit" -> edit(sender, args);
			case "edge" -> edge(sender, args);
			case "custom" -> custom(sender, args);
			case "list" -> list(sender);
			case "tp" -> teleport(sender, args);
			case "show" -> toggleShow(sender, args);
			case "perf" ->
			{
				msg(sender, "BlockBlocker performance:", NamedTextColor.YELLOW);
				List<String> lines = new ArrayList<>(Perf.report());
				if(sender instanceof Player p && clientBarriers != null)
					lines.add("Shulkers sent to " + p.getName() + " right now: " + clientBarriers.shulkerCount(p.getUniqueId()));
				for(String line : lines)
				{
					sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
					getLogger().info("[perf] " + line);
				}
				msg(sender, "Run it again later to measure the time in between (e.g. play/edit for 30 s, then /bb perf).", NamedTextColor.GRAY);
			}
			case "pick" -> togglePick(sender);
			case "debug" -> toggleDebug(sender);
			case "shulkers" -> toggleShulkers(sender);
			case "info" -> info(sender);
			case "why" -> why(sender);
			case "off" -> switchBarrier(sender, args, true);
			case "on" -> switchBarrier(sender, args, false);
			case "clear" ->
			{
				// Deletes every saved barrier in every world: only with "confirm"
				if(args.length < 2 || !args[1].equalsIgnoreCase("confirm"))
				{
					msg(sender, "This deletes ALL " + store.all().size() + " saved barrier(s) in every world. Type /block clear confirm to do it.", NamedTextColor.RED);
					return true;
				}
				int n = store.clear();
				saveStore();
				msg(sender, "Removed " + n + " barrier(s).", NamedTextColor.GREEN);
			}
			case "reload" ->
			{
				reloadConfig();
				grimIgnoreNear = getConfig().getBoolean("grim-ignore-near-shulkers", true);
				List<String> addedKeys = mergeDefaultConfig();
				respectPartial = !collidersWanted && getConfig().getBoolean("respect-partial-blocks", true);
				store.setRespectPartial(respectPartial);
				store.setThinBlocks(getConfig().getStringList("thin-blocks"));
		store.setEnforceShapes(!"push".equalsIgnoreCase(getConfig().getString("shape-mode", "block")));
		store.setFillCell(getConfig().getBoolean("push-fill-cell", false));
		store.setBlockFill(getConfig().getBoolean("block-fill-cell", false));
		store.setConnectShapes(getConfig().getBoolean("connect-shapes", true));
		store.setCopyPartial(getConfig().getBoolean("shape-copy-partial-blocks", true));
		loadPickItem();
				// An editor change from the last second is written first (the load would otherwise throw it away)
				if(customEditor != null)
					customEditor.flushSave();
				store.load();
				refreshMzBridge();
				offTasks.values().forEach(BukkitTask::cancel);
				offTasks.clear();
				store.enableAll();
				if(mobBlocker != null)
					mobBlocker.start();
				msg(sender, "Reloaded " + store.all().size() + " barrier(s).", NamedTextColor.GREEN);
				if(!addedKeys.isEmpty())
					msg(sender, "Added missing config options with their default values: " + String.join(", ", addedKeys), NamedTextColor.YELLOW);
			}
			default -> help(sender, label);
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Pick / info / off / on: find a barrier by looking at it, and switch it off for a while (e.g. to use a sign inside it).
	// ---------------------------------------------------------------------------------------------------------------

	private static final long PICK_WINDOW_MS = 30_000;
	private static final double PICK_REACH = 8.0;

	/** Players currently in "/block pick" mode -> when it expires. Concurrent: the packet listener runs off the main thread. */
	private final Map<UUID, Long> pickers = new ConcurrentHashMap<>();
	/** The barrier each player picked last, so "/block off" with no name knows which one they mean. */
	private final Map<UUID, String> lastPicked = new HashMap<>();
	/** Pending automatic switch-backs ("world/name" -> task). */
	private final Map<String, BukkitTask> offTasks = new HashMap<>();

	private void togglePick(CommandSender sender)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		if(pickers.remove(player.getUniqueId()) != null)
		{
			msg(sender, "Pick mode cancelled.", NamedTextColor.YELLOW);
			return;
		}
		pickers.put(player.getUniqueId(), System.currentTimeMillis() + PICK_WINDOW_MS);
		msg(sender, "Right-click the barrier you want to identify (30s). Your click will NOT activate signs or blocks. /block pick again to cancel.", NamedTextColor.GREEN);
	}

	/** True if this player was picking (and is now done picking); false if they weren't or it expired. Thread-safe. */
	private boolean consumePick(Player player)
	{
		Long expires = pickers.remove(player.getUniqueId());
		return expires != null && expires >= System.currentTimeMillis();
	}

	/**
	 * Admins holding the pick item right now, refreshed on the main thread every 5 ticks. The packet listener (network thread)
	 * only reads this set: it used to check permissions and read the inventory off the main thread on every placement packet.
	 */
	private final java.util.Set<UUID> pickHolders = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private void refreshPickHolders()
	{
		java.util.Set<UUID> now = new java.util.HashSet<>();
		for(Player p : getServer().getOnlinePlayers())
			if(holdsPickItem(p))
				now.add(p.getUniqueId());
		pickHolders.retainAll(now);
		pickHolders.addAll(now);
	}

	/** Called from the network thread by the packet listener: hand the work back to the main thread. */
	private boolean consumePickFromPacket(Player player)
	{
		if(pickHolders.contains(player.getUniqueId()) && (customEditor == null || !customEditor.isEditing(player.getUniqueId())))
		{
			// (sneaking: swap the barrier between normal and follow-shape blocking instead of just reporting it)
			if(firstItemPick(player))
				Bukkit.getScheduler().runTask(this, () ->
				{
					if(player.isSneaking())
						toggleShapeByPick(player);
					else
						reportPick(player, false);
				});
			return true;
		}
		if(!consumePick(player))
			return false;
		Bukkit.getScheduler().runTask(this, () -> reportPick(player, true));
		return true;
	}

	/** Holding the pick item (config pick-item, default BONE) makes every right-click a /block pick, for admins. */
	private boolean holdsPickItem(Player player)
	{
		if(pickItem == null || !player.hasPermission(PERM))
			return false;
		if(customEditor != null && customEditor.isEditing(player.getUniqueId()))
			return false; // the editor needs right-clicks for placing cubes
		return player.getInventory().getItemInMainHand().getType() == pickItem;
	}

	/** Last pick-item report per player: one right-click arrives as several events/packets, so report only once. */
	private final Map<UUID, Long> lastItemPick = new ConcurrentHashMap<>();
	private static final long ITEM_PICK_COOLDOWN_MS = 400;

	/** True for the first pick-item click in a short window; false for the copies of that same click. Thread-safe. */
	private boolean firstItemPick(Player player)
	{
		long now = System.currentTimeMillis();
		Long last = lastItemPick.put(player.getUniqueId(), now);
		return last == null || now - last > ITEM_PICK_COOLDOWN_MS;
	}

	/** Item that picks on right-click (null = off). */
	private volatile Material pickItem = Material.BONE;

	private void loadPickItem()
	{
		String s = getConfig().getString("pick-item", "BONE");
		pickItem = s == null || s.isBlank() || s.equalsIgnoreCase("none") ? null : Material.matchMaterial(s);
		if(pickItem == null && s != null && !s.isBlank() && !s.equalsIgnoreCase("none"))
			getLogger().warning("pick-item: unknown item '" + s + "', right-click picking is off.");
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPickClick(PlayerInteractEvent event)
	{
		if(event.getHand() != EquipmentSlot.HAND)
			return;
		Action action = event.getAction();
		if(action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR)
			return;
		Player player = event.getPlayer();
		boolean item = holdsPickItem(player);
		if(!item && !consumePick(player))
			return;
		// Don't let a pick click also buy a gun, open a door, etc.
		event.setCancelled(true);
		if(item && player.isSneaking())
		{
			if(firstItemPick(player))
				toggleShapeByPick(player);
			return;
		}
		if(!item || firstItemPick(player))
			reportPick(player, !item);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Click-through: a barrier must not stop players using what is behind it (e.g. a sign that buys a gun).
	// ---------------------------------------------------------------------------------------------------------------

	private final Map<UUID, Long> lastRelay = new HashMap<>();

	/** Is this position really air but inside a barrier (so the client is pointing at one of our fake barrier blocks)? */
	private boolean isBarrierAirCell(Player player, int x, int y, int z)
	{
		World world = player.getWorld();
		if(!world.isChunkLoaded(x >> 4, z >> 4) || !world.getBlockAt(x, y, z).getType().isAir())
			return false;
		for(Barrier b : store.barriersIn(world.getName()))
			if(b.contains(x, y, z))
				return true;
		if(mzBridge != null)
			for(Barrier b : mzBridge.barriersFor(player))
				if(b.contains(x, y, z))
					return true;
		return false;
	}

	/**
	 * The player right-clicked our invisible barrier instead of the block behind it. Find the real block along their line
	 * of sight (our fake blocks and shulkers are not real, so the trace passes straight through them) and deliver the click
	 * to it as a normal right-click event, so plugins such as sign shops react exactly as if it had been clicked directly.
	 * Only plugin reactions are passed on; vanilla actions (opening a chest, flipping a lever) are not performed.
	 */
	private void relayClick(Player player)
	{
		if(!player.isOnline() || !getConfig().getBoolean("click-through", true))
			return;
		long now = System.currentTimeMillis();
		Long last = lastRelay.get(player.getUniqueId());
		if(last != null && now - last < 200)
			return; // one click can arrive as several packets
		Location eye = player.getEyeLocation();
		// The player's own reach (4.5 in survival), not a flat 5 blocks: a click passed through may not reach further than a
		// normal click could
		var reachAttr = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
		double reach = reachAttr != null ? reachAttr.getValue() : 4.5;
		RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), reach, FluidCollisionMode.NEVER, false);
		if(hit == null || hit.getHitBlock() == null)
			return;
		lastRelay.put(player.getUniqueId(), now);
		BlockFace face = hit.getHitBlockFace() == null ? BlockFace.SELF : hit.getHitBlockFace();
		Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(), hit.getHitBlock(), face, EquipmentSlot.HAND));
	}

	/** Every arm swing: the trigger for attack-through (see relayAttack). */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onSwing(PlayerAnimationEvent event)
	{
		if(event.getAnimationType() != PlayerAnimationType.ARM_SWING)
			return;
		Player player = event.getPlayer();
		if(customEditor != null && customEditor.isEditing(player.getUniqueId()))
		{
			customEditor.click(player, false);
			return;
		}
		boolean active = clientBarriers != null && clientBarriers.isActive(player.getUniqueId());
		Long last = lastRealAttack.get(player.getUniqueId());
		boolean real = last != null && System.currentTimeMillis() - last < 150;
		dbg(player, "swing seen. Near barrier cells: " + active
				+ (collidersWanted ? "" : ". shulker-colliders is OFF, so attack-through cannot tell real hits from misses and is disabled")
				+ (real ? ". You just hit a real entity, so nothing to relay." : "."));
		// Needs the packet listener (shulker-colliders) to know whether the client already hit something real.
		if(!collidersWanted || !active || real)
			return;
		relayAttack(player);
	}

	/** When each player last hit a real entity (network thread writes it, so concurrent). */
	private final Map<UUID, Long> lastRealAttack = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastAttackRelay = new HashMap<>();

	/**
	 * A swing that did not hit an entity, while the player is near our invisible barriers: their client probably aimed at a
	 * fake barrier block or shulker, which swallows the swing and the attack never happens. Find the creature that is really
	 * in their line of sight and attack it for them, as a normal player attack (damage, cooldown and events all as usual).
	 * Only when one of our invisible cells is actually in the way - otherwise the client would have attacked on its own -
	 * and never through a real block.
	 */
	private void relayAttack(Player player)
	{
		if(!player.isOnline())
			return;
		if(!getConfig().getBoolean("attack-through", true))
		{
			dbg(player, "STOP: attack-through is false in config.yml.");
			return;
		}
		long now = System.currentTimeMillis();
		Long last = lastAttackRelay.get(player.getUniqueId());
		if(last != null && now - last < 100)
		{
			dbg(player, "STOP: I already relayed an attack for you less than 100 ms ago.");
			return;
		}

		var range = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
		double reach = range == null ? 3.0 : range.getValue();
		World world = player.getWorld();
		Location eye = player.getEyeLocation();
		Vector dir = eye.getDirection();

		RayTraceResult hit = world.rayTraceEntities(eye, dir, reach, 0.0,
				e -> e != player && e instanceof LivingEntity living && !(e instanceof Player) && !living.isDead());
		if(hit == null || hit.getHitEntity() == null)
		{
			dbg(player, "STOP: no creature is in your exact line of sight within reach " + fmt(reach) + " blocks (the crosshair line from your eyes must pass through its hitbox).");
			return;
		}
		var target = hit.getHitEntity();
		double distance = eye.toVector().distance(hit.getHitPosition());
		dbg(player, "line of sight hits a " + target.getType() + " " + fmt(distance) + " blocks from your eyes.");

		List<Barrier> ours = new ArrayList<>(store.barriersIn(world.getName()));
		if(mzBridge != null)
			ours.addAll(mzBridge.barriersFor(player));
		// Only when one of our invisible cells is in the way: otherwise the client would have attacked by itself.
		if(!crossesOurCell(ours, eye, dir, distance))
		{
			dbg(player, "STOP: none of the " + ours.size() + " barrier cell(s) I know about for you are between you and it. "
					+ (mzBridge == null ? "(mz-barriers is OFF or MZ not found.)" : "(mz-barriers is on: cells only count while you are in an MZ game, in that game's world.)"));
			return;
		}
		// Real blocks still stop the attack, exactly like vanilla - except those standing inside a barrier cell (the barrier
		// itself: barrier blocks, half blocks, fences, boards...), which a barrier must never protect a zombie with.
		var blocker = blockingBlock(world, eye, dir, distance, ours);
		if(blocker != null)
		{
			dbg(player, "STOP: a real " + blocker.getType() + " at " + blocker.getX() + "," + blocker.getY() + "," + blocker.getZ() + " is in the way and is NOT part of a barrier cell I know.");
			return;
		}

		lastAttackRelay.put(player.getUniqueId(), now);
		LivingEntity living = (LivingEntity) target;
		double healthBefore = living.getHealth();
		double meleeRange = -1;
		var mz = Bukkit.getPluginManager().getPlugin("MinecraftZombies");
		if(mz != null)
			meleeRange = mz.getConfig().getDouble("config.gameSettings.MeleeRange", -1);
		double feet = target.getLocation().distance(player.getLocation());
		dbg(player, "RELAYING the attack on the " + target.getType() + ". Holding " + player.getInventory().getItemInMainHand().getType()
				+ ". Feet-to-feet distance " + fmt(feet) + (meleeRange >= 0 ? " (MinecraftZombies only damages within MeleeRange = " + fmt(meleeRange) + (feet > meleeRange ? " -> TOO FAR, MZ will ignore the hit" : " -> ok") + ")" : "")
				+ ". Sword cooldown " + fmt(player.getAttackCooldown()) + ".");
		player.attack(target);
		if(debuggers.contains(player.getUniqueId()))
			Bukkit.getScheduler().runTaskLater(this, () -> dbg(player, living.isDead() ? "RESULT: the creature died." : "RESULT: health " + fmt(healthBefore) + " -> " + fmt(living.getHealth())
					+ (living.getHealth() < healthBefore ? " (damage applied)." : " (NO damage: MinecraftZombies or another plugin/mod cancelled it - check the distance and held item above, and any melee-cooldown plugin).")), 2L);
	}

	private static String fmt(double v)
	{
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private final Set<UUID> debuggers = ConcurrentHashMap.newKeySet();

	/** Debug line for a player who ran /block debug. Main thread. */
	private void dbg(Player player, String text)
	{
		if(debuggers.contains(player.getUniqueId()))
			player.sendMessage(Component.text("[BB debug] ", NamedTextColor.DARK_AQUA).append(Component.text(text, NamedTextColor.GRAY)));
	}

	/** Same, from the network thread. */
	private void dbgAsync(Player player, String text)
	{
		if(debuggers.contains(player.getUniqueId()))
			Bukkit.getScheduler().runTask(this, () -> dbg(player, text));
	}

	private void toggleDebug(CommandSender sender)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		if(debuggers.remove(player.getUniqueId()))
			msg(sender, "Debug off.", NamedTextColor.YELLOW);
		else
		{
			debuggers.add(player.getUniqueId());
			msg(sender, "Debug ON: EVERY arm swing now prints a '[BB debug] swing seen' line, followed by what the plugin did with it. If you see no line at all when you swing, the plugin is not receiving your swings. /block debug again to stop.", NamedTextColor.GREEN);
		}
	}

	private static boolean inAnyBarrier(List<Barrier> barriers, int x, int y, int z)
	{
		for(Barrier b : barriers)
			if(b.contains(x, y, z))
				return true;
		return false;
	}

	/** Does the line from the player's eyes to that distance pass through one of our invisible barrier cells? */
	private static boolean crossesOurCell(List<Barrier> barriers, Location eye, Vector dir, double distance)
	{
		if(barriers.isEmpty())
			return false;
		for(double t = 0; t <= distance; t += 0.1)
			if(inAnyBarrier(barriers, (int) Math.floor(eye.getX() + dir.getX() * t), (int) Math.floor(eye.getY() + dir.getY() * t), (int) Math.floor(eye.getZ() + dir.getZ() * t)))
				return true;
		return false;
	}

	/**
	 * Is there a real block between the eyes and the target that is not part of a barrier? Blocks inside a barrier cell are
	 * stepped over (the ray carries on from the far side of that cell), so a zombie behind boards, half blocks, fences or
	 * bars can still be hit, while an ordinary wall still protects it.
	 */
	private org.bukkit.block.Block blockingBlock(World world, Location eye, Vector dir, double distance, List<Barrier> ours)
	{
		boolean allowFull = getConfig().getBoolean("attack-through-full-blocks", true);
		double done = 0;
		for(int i = 0; i < 32 && done < distance; i++)
		{
			Location start = eye.clone().add(dir.clone().multiply(done));
			RayTraceResult hit = world.rayTraceBlocks(start, dir, distance - done, FluidCollisionMode.NEVER, true);
			if(hit == null || hit.getHitBlock() == null)
				return null;
			var block = hit.getHitBlock();
			if(!inAnyBarrier(ours, block.getX(), block.getY(), block.getZ()))
				return block;
			if(!allowFull && BarrierStore.isFullCube(block))
				return block;
			done += exitDistance(start, dir, block.getX(), block.getY(), block.getZ()) + 0.001; // carry on past this cell
		}
		return null;
	}

	/** How far along the ray from origin until it leaves the 1x1x1 cell at (cx, cy, cz). */
	private static double exitDistance(Location origin, Vector dir, int cx, int cy, int cz)
	{
		double[] o = {origin.getX(), origin.getY(), origin.getZ()};
		double[] d = {dir.getX(), dir.getY(), dir.getZ()};
		int[] c = {cx, cy, cz};
		double t = Double.MAX_VALUE;
		for(int i = 0; i < 3; i++)
		{
			if(Math.abs(d[i]) < 1.0E-9)
				continue;
			t = Math.min(t, ((d[i] > 0 ? c[i] + 1 : c[i]) - o[i]) / d[i]);
		}
		return Math.max(t, 0);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event)
	{
		UUID id = event.getPlayer().getUniqueId();
		pickers.remove(id);
		lastItemPick.remove(id);
		debuggers.remove(id);
		lastPicked.remove(id);
		lastRelay.remove(id);
		lastRealAttack.remove(id);
		lastAttackRelay.remove(id);
	}

	private void info(CommandSender sender)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		reportPick(player, false);
	}

	/**
	 * The nearest switched-on barrier along the player's line of sight. Switched-off ones are looked through (so the
	 * barriers behind or overlapping them can still be picked), and only picked when there is no switched-on one, so they
	 * can be switched back on.
	 */
	/**
	 * /block why: for the barrier cell you look at, what every barrier there (BlockBlocker's and MZ's window cells) makes of
	 * it and why: the block, the mode, what it copies, and what the client is sent (full block / thin shulkers / nothing).
	 */
	private void why(CommandSender sender)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		java.io.File jar = getFile();
		msg(player, "Plugin build: " + new java.util.Date(jar.lastModified()), NamedTextColor.DARK_GRAY);
		List<Barrier> list = new ArrayList<>(store.allBarriersIn(player.getWorld().getName()));
		if(mzBridge != null)
			list.addAll(mzBridge.barriersFor(player));
		Location eye = player.getEyeLocation();
		var dir = eye.getDirection();
		for(double t = 0; t <= PICK_REACH; t += 0.05)
		{
			int x = (int) Math.floor(eye.getX() + dir.getX() * t), y = (int) Math.floor(eye.getY() + dir.getY() * t), z = (int) Math.floor(eye.getZ() + dir.getZ() * t);
			List<Barrier> here = new ArrayList<>();
			for(Barrier b : list)
				if(b.contains(x, y, z))
					here.add(b);
			if(here.isEmpty())
				continue;
			org.bukkit.block.Block block = player.getWorld().getBlockAt(x, y, z);
			msg(player, "Cell " + x + ", " + y + ", " + z + ": " + block.getType().name().toLowerCase(Locale.ROOT)
					+ (store.isThin(block) ? " (thin)" : store.copiesShape(block) ? " (partial)" : BarrierStore.isFullCube(block) ? " (full block)" : ""), NamedTextColor.YELLOW);
			for(Barrier b : here)
			{
				boolean mz = MzBridge.CELL_NAME.equals(b.name());
				String mode = mz ? "MZ window cell" : store.getCustom(b) != null ? "custom" : store.getEdge(b) != null ? "edge"
						: store.hasShapes(b) ? "follow shape" : "normal";
				String what;
				if(mz)
					what = block.getType().isAir() ? "whole cell (window broken)" : store.isThin(block) ? "the block's own collision (thin window)"
							: BarrierStore.isFullCube(block) ? "the block itself" : "whole cell (full shulker: window block isn't bars/pane/fence/wall)";
				else if(store.getCustom(b) != null || store.getEdge(b) != null)
					what = "its own exact boxes";
				else if(BarrierStore.isOpen(block) && (block.getType().isAir() || store.shapeIn(b, block) != null))
				{
					List<int[]> shape = store.isBlockFill() ? null : store.shapeIn(b, block);
					org.bukkit.block.Block src = store.sourceFor(b, block);
					what = shape == null ? "whole cell (empty, nothing to copy" + (store.hasShapes(b) ? ", not in a line of thin blocks)" : ")")
							: shapeText(shape) + (src != null ? ", copied from " + src.getType().name().toLowerCase(Locale.ROOT) + " at y " + src.getY() : ", continuing the thin line");
				}
				else if(BarrierStore.isFullCube(block))
					what = "the block itself";
				else if(store.hasShapes(b) && store.isThin(block))
				{
					List<int[]> extra = store.thinCellExtras(b, block);
					what = "the block's own collision" + (extra.isEmpty() ? "" : " + " + shapeText(extra));
				}
				else if(store.hasShapes(b) && store.copiesShape(block))
				{
					List<int[]> fp = store.footprints(block);
					what = fp == null ? "nothing" : shapeText(fp) + " (its outline, whole cell height)";
				}
				else
					what = "whole cell (full shulker: " + (store.hasShapes(b) ? "block isn't copied" : "normal barrier") + ")";
				player.sendMessage(Component.text("  '" + b.name() + "' ", NamedTextColor.AQUA)
						.append(Component.text("[" + mode + (store.isDisabled(b) ? ", OFF" : "") + "] ", NamedTextColor.GRAY))
						.append(Component.text(what, NamedTextColor.WHITE)));
			}
			return;
		}
		msg(player, "No barrier cell where you're looking (reach " + (int) PICK_REACH + " blocks).", NamedTextColor.RED);
	}

	/** Footprints as text: a full cell, or each piece's size in sixteenths. */
	private static String shapeText(List<int[]> fp)
	{
		for(int[] f : fp)
			if(f[0] == 0 && f[1] == 0 && f[2] == 16 && f[3] == 16)
				return "whole cell (full shulker: the copied outline covers the whole block)";
		StringBuilder sb = new StringBuilder("thin shape: ");
		for(int i = 0; i < fp.size(); i++)
		{
			int[] f = fp.get(i);
			sb.append(i == 0 ? "" : ", ").append(f[2] - f[0]).append("x").append(f[3] - f[1]);
		}
		return sb.append(" (16ths)").toString();
	}

	private Barrier lookedAt(Player player)
	{
		List<Barrier> list = store.allBarriersIn(player.getWorld().getName());
		if(list.isEmpty())
			return null;
		Location eye = player.getEyeLocation();
		var dir = eye.getDirection();
		Barrier firstOff = null;
		for(double t = 0; t <= PICK_REACH; t += 0.1)
		{
			int x = (int) Math.floor(eye.getX() + dir.getX() * t);
			int y = (int) Math.floor(eye.getY() + dir.getY() * t);
			int z = (int) Math.floor(eye.getZ() + dir.getZ() * t);
			for(Barrier b : list)
				if(b.contains(x, y, z))
				{
					if(!store.isDisabled(b))
						return b;
					if(firstOff == null)
						firstOff = b;
				}
		}
		return firstOff;
	}

	private void reportPick(Player player, boolean rearmOnMiss)
	{
		if(!player.isOnline())
			return;
		Barrier b = lookedAt(player);
		if(b == null)
		{
			msg(player, "No barrier where you're looking (reach " + (int) PICK_REACH + " blocks)." + (rearmOnMiss ? " Try again, or /block pick to cancel." : ""), NamedTextColor.RED);
			if(rearmOnMiss)
				pickers.put(player.getUniqueId(), System.currentTimeMillis() + PICK_WINDOW_MS);
			return;
		}
		lastPicked.put(player.getUniqueId(), b.name());
		boolean off = store.isDisabled(b);
		String name = b.name();
		player.sendMessage(Component.text("[BlockBlocker] ", NamedTextColor.DARK_GRAY)
				.append(Component.text("Barrier '" + name + "'", NamedTextColor.AQUA))
				.append(Component.text(off ? " (OFF)" : " (on)", off ? NamedTextColor.RED : NamedTextColor.GREEN))
				.append(Component.text(" - " + b.describe(), NamedTextColor.GRAY)));
		player.sendMessage(Component.text("   ")
				.append(Component.text("[Switch off]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/block off " + name)))
				.append(Component.text("  "))
				.append(Component.text("[Switch on]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/block on " + name)))
				.append(Component.text("  "))
				.append(Component.text("[Delete]", NamedTextColor.DARK_RED)
						.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Delete this barrier (asks first)", NamedTextColor.GRAY)))
						.clickEvent(ClickEvent.callback(a -> askDelete(player, name)))));
		if(store.getCustom(b) != null)
		{
			// Custom barriers can be edited straight from here (same command as /block custom edit <name>)
			player.sendMessage(Component.text("   Type: ", NamedTextColor.GRAY)
					.append(Component.text("custom", NamedTextColor.LIGHT_PURPLE))
					.append(Component.text("  "))
					.append(Component.text("[Edit]", NamedTextColor.GOLD)
							.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Edit this custom barrier's shape", NamedTextColor.GRAY)))
							.clickEvent(ClickEvent.runCommand("/block custom edit " + name))));
			return; // own exact shape: the blocking mode doesn't apply
		}
		if(store.getEdge(b) != null)
		{
			player.sendMessage(Component.text("   Type: ", NamedTextColor.GRAY).append(Component.text("edge", NamedTextColor.LIGHT_PURPLE)));
			return;
		}
		player.sendMessage(Component.text("   Type: ", NamedTextColor.GRAY).append(Component.text("normal", NamedTextColor.WHITE)));
		boolean shaped = store.hasShapes(b);
		player.sendMessage(Component.text("   Blocking: ", NamedTextColor.GRAY)
				.append(Component.text(shaped ? "follows shape (like addshape)" : "normal, whole blocks (like add)", NamedTextColor.YELLOW))
				.append(Component.text("  "))
				.append(Component.text("[Normal]", shaped ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY).clickEvent(ClickEvent.runCommand("/block shapes " + name + " off")))
				.append(Component.text(" "))
				.append(Component.text("[Follow shape]", shaped ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE).clickEvent(ClickEvent.runCommand("/block shapes " + name + " on")))
				.append(Component.text("  (sneak + right-click with the " + (pickItem == null ? "pick item" : pickItem.name().toLowerCase(Locale.ROOT)) + " to swap)", NamedTextColor.DARK_GRAY)));
		if(!shaped)
			return;
		// Auto-connect arms per side: green = connects that way, gray = switched off. Click to flip.
		java.util.Set<BlockFace> off = store.noConnect(b);
		Component row = Component.text("   Connect: ", NamedTextColor.GRAY);
		for(BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			boolean on = !off.contains(f);
			String side = f.name().toLowerCase(Locale.ROOT);
			row = row.append(Component.text("[" + Character.toUpperCase(side.charAt(0)) + side.substring(1) + "]", on ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
					.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text((on ? "Connects " : "Doesn't connect ") + side + ". Click to " + (on ? "stop" : "allow") + " it.", NamedTextColor.GRAY)))
					.clickEvent(ClickEvent.runCommand("/block connect " + name + " " + side + " " + (on ? "off" : "on"))))
					.append(Component.text(" "));
		}
		player.sendMessage(row.append(Component.text(" (you face " + facing(player).name().toLowerCase(Locale.ROOT) + ")", NamedTextColor.DARK_GRAY)));
	}

	/** The pick report's [Delete]: asks once more, since it can't be undone. */
	private void askDelete(Player player, String name)
	{
		if(!player.isOnline() || !player.hasPermission(PERM))
			return;
		player.sendMessage(Component.text("[BlockBlocker] ", NamedTextColor.DARK_GRAY)
				.append(Component.text("Delete barrier '" + name + "'? This can't be undone. ", NamedTextColor.RED))
				.append(Component.text("[Yes, delete it]", NamedTextColor.DARK_RED)
						.clickEvent(ClickEvent.callback(a ->
						{
							if(!player.isOnline() || !player.hasPermission(PERM))
								return;
							remove(player, new String[] {"remove", name});
							lastPicked.remove(player.getUniqueId(), name);
						}, net.kyori.adventure.text.event.ClickCallback.Options.builder().lifetime(java.time.Duration.ofSeconds(30)).build())))
				.append(Component.text("  "))
				.append(Component.text("[Cancel]", NamedTextColor.GRAY).clickEvent(ClickEvent.callback(a -> msg(player, "Kept '" + name + "'.", NamedTextColor.GRAY)))));
	}

	/** Sneak + right-click with the pick item: swap the barrier you look at between normal and follow-shape blocking. */
	private void toggleShapeByPick(Player player)
	{
		if(!player.isOnline())
			return;
		Barrier b = lookedAt(player);
		if(b == null)
		{
			msg(player, "No barrier where you're looking (reach " + (int) PICK_REACH + " blocks).", NamedTextColor.RED);
			return;
		}
		lastPicked.put(player.getUniqueId(), b.name());
		if(store.getCustom(b) != null || store.getEdge(b) != null)
		{
			msg(player, "'" + b.name() + "' is a custom/edge barrier with its own exact shape, so normal/follow-shape doesn't apply to it.", NamedTextColor.GRAY);
			return;
		}
		shapes(player, new String[] {"shapes", b.name(), store.hasShapes(b) ? "off" : "on"});
	}

	/** /block off|on [name] [seconds] */
	private void switchBarrier(CommandSender sender, String[] args, boolean off)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		String name = args.length >= 2 ? args[1] : lastPicked.get(player.getUniqueId());
		if(name == null)
		{
			msg(sender, "Which barrier? Use /block " + (off ? "off" : "on") + " <name>, or /block pick first.", NamedTextColor.RED);
			return;
		}
		String world = player.getWorld().getName();
		if(!store.setDisabled(world, name, off))
		{
			msg(sender, "No barrier named '" + name + "' in this world. Use /block list.", NamedTextColor.RED);
			return;
		}

		String taskKey = world + "/" + name;
		BukkitTask old = offTasks.remove(taskKey);
		if(old != null)
			old.cancel();
		lastPicked.put(player.getUniqueId(), name);

		if(!off)
		{
			msg(sender, "Barrier '" + name + "' is back on.", NamedTextColor.GREEN);
			return;
		}

		int seconds = getConfig().getInt("disable-seconds", 60);
		if(args.length >= 3)
		{
			try
			{
				seconds = Math.max(0, Integer.parseInt(args[2]));
			}
			catch(NumberFormatException e)
			{
				msg(sender, "Seconds must be a number (0 = until you switch it on).", NamedTextColor.RED);
				store.setDisabled(world, name, false);
				return;
			}
		}
		if(seconds > 0)
		{
			offTasks.put(taskKey, Bukkit.getScheduler().runTaskLater(this, () -> {
				offTasks.remove(taskKey);
				if(store.setDisabled(world, name, false))
					msg(player, "Barrier '" + name + "' switched back on.", NamedTextColor.GREEN);
			}, seconds * 20L));
			msg(sender, "Barrier '" + name + "' is OFF for " + seconds + "s. /block on " + name + " to switch it back sooner.", NamedTextColor.YELLOW);
		}
		else
			msg(sender, "Barrier '" + name + "' is OFF until you run /block on " + name + " (or restart).", NamedTextColor.YELLOW);
	}

	/** Debug: makes the fake shulkers visible (glowing, even through walls) for everyone, so you can see where they are. */
	private void toggleShulkers(CommandSender sender)
	{
		boolean on = !ColliderPackets.isVisible();
		ColliderPackets.setVisible(on);
		if(clientBarriers != null)
			clientBarriers.refreshVisibility();
		msg(sender, on ? "Shulkers are now VISIBLE and glowing (shown to everyone near a barrier). Run /block shulkers again to hide them."
				: "Shulkers are hidden again.", NamedTextColor.GREEN);
		if(sender instanceof Player p && clientBarriers != null)
			msg(sender, "You are currently shown " + clientBarriers.colliderCount(p.getUniqueId()) + " shulkers.", NamedTextColor.GRAY);
	}

	/**
	 * Adds every option that the bundled config.yml has but the server's config.yml lacks (new features), with its default value
	 * and comment. Existing values and comments are left alone. @return the keys that were added.
	 */
	private List<String> mergeDefaultConfig()
	{
		List<String> added = new ArrayList<>();
		try(var in = getResource("config.yml"))
		{
			if(in == null)
				return added;
			var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
			var config = getConfig();
			for(String key : defaults.getKeys(false))
			{
				if(config.isSet(key) && config.contains(key, true))
					continue;
				config.set(key, defaults.get(key));
				config.setComments(key, defaults.getComments(key));
				added.add(key);
			}
			if(!added.isEmpty())
			{
				saveConfig();
				getLogger().info("Added missing config options: " + String.join(", ", added));
			}
		}
		catch(Exception e)
		{
			getLogger().log(java.util.logging.Level.WARNING, "Could not add missing config options.", e);
		}
		return added;
	}

	/** Turns any accepted spelling (long or short) of a subcommand into its one canonical name. */
	private static String canonical(String arg)
	{
		return switch(arg.toLowerCase(Locale.ROOT))
		{
			case "add", "a", "create", "set" -> "add";
			case "addshape", "addshapes", "as" -> "addshape";
			case "shapes", "shape" -> "shapes";
			case "connect", "conn" -> "connect";
			case "remove", "rm", "delete", "del" -> "remove";
			case "edit", "e", "modify", "mod" -> "edit";
			case "edge" -> "edge";
			case "custom", "cu", "c" -> "custom";
			case "list", "ls", "l" -> "list";
			case "tp", "t", "goto" -> "tp";
			case "show", "s" -> "show";
			case "perf", "lag" -> "perf";
			case "pick", "p" -> "pick";
			case "debug", "dbg" -> "debug";
			case "shulkers", "sh" -> "shulkers";
			case "info", "i" -> "info";
			case "why", "cell" -> "why";
			case "off", "disable" -> "off";
			case "on", "enable" -> "on";
			case "clear", "cl" -> "clear";
			case "reload", "rl" -> "reload";
			default -> arg.toLowerCase(Locale.ROOT);
		};
	}

	/** The side (north/south/east/west) the player faces. */
	private static BlockFace facing(Player player)
	{
		float yaw = (player.getLocation().getYaw() % 360 + 360) % 360;
		return yaw < 45 || yaw >= 315 ? BlockFace.SOUTH : yaw < 135 ? BlockFace.WEST : yaw < 225 ? BlockFace.NORTH : BlockFace.EAST;
	}

	/**
	 * /block connect <name> <north|south|east|west|me> [on|off]: does this follow-shape barrier auto-connect (add arms
	 * between bars, fences, walls...) toward that side? Off removes the arms pointing that way.
	 */
	private void connect(CommandSender sender, String[] args)
	{
		String usage = "Usage: /block connect <name> <north|south|east|west|me> [on|off]";
		if(args.length < 3)
		{
			if(args.length == 2)
			{
				Barrier b = sender instanceof Player p ? store.find(p.getWorld().getName(), args[1]) : store.get(args[1]);
				if(b != null)
				{
					java.util.Set<BlockFace> off = store.noConnect(b);
					msg(sender, "'" + b.name() + "' doesn't connect: " + (off.isEmpty() ? "nothing switched off" : off.stream().map(f -> f.name().toLowerCase(Locale.ROOT)).toList()) + ".", NamedTextColor.YELLOW);
					return;
				}
			}
			msg(sender, usage, NamedTextColor.RED);
			return;
		}
		Barrier b = sender instanceof Player p ? store.find(p.getWorld().getName(), args[1]) : store.get(args[1]);
		if(b == null)
		{
			msg(sender, "No barrier named '" + args[1] + "' in this world. Use /block list.", NamedTextColor.RED);
			return;
		}
		String sideArg = args[2].toLowerCase(Locale.ROOT);
		BlockFace side;
		if(sideArg.equals("me"))
		{
			if(!(sender instanceof Player p))
			{
				msg(sender, "Only players can use 'me'.", NamedTextColor.RED);
				return;
			}
			side = facing(p);
		}
		else
			side = switch(sideArg)
			{
				case "north", "n" -> BlockFace.NORTH;
				case "south", "s" -> BlockFace.SOUTH;
				case "east", "e" -> BlockFace.EAST;
				case "west", "w" -> BlockFace.WEST;
				default -> null;
			};
		if(side == null)
		{
			msg(sender, usage, NamedTextColor.RED);
			return;
		}
		boolean on;
		if(args.length >= 4)
		{
			String v = args[3].toLowerCase(Locale.ROOT);
			if(!v.equals("on") && !v.equals("off"))
			{
				msg(sender, usage, NamedTextColor.RED);
				return;
			}
			on = v.equals("on");
		}
		else
			on = store.noConnect(b).contains(side); // no on/off: flip it
		store.setConnect(b, side, on);
		saveStore();
		if(clientBarriers != null)
			clientBarriers.refreshVisibility();
		String sideName = side.name().toLowerCase(Locale.ROOT);
		msg(sender, "'" + b.name() + "' " + (on ? "connects " + sideName + " again." : "no longer auto-connects " + sideName + "."), NamedTextColor.GREEN);
		if(store.getCustom(b) != null || store.getEdge(b) != null || !store.hasShapes(b))
			msg(sender, "(It isn't a follow-shape barrier, so this has no effect on it.)", NamedTextColor.GRAY);
	}

	/** /block shapes <name> [on|off]: does this barrier copy bar/fence/pane/wall shapes into its air cells? */
	private void shapes(CommandSender sender, String[] args)
	{
		if(args.length < 2)
		{
			msg(sender, "Usage: /block shapes <name> [on|off]", NamedTextColor.RED);
			return;
		}
		// "/block shapes on|off" with no name: the barrier last picked with /block pick or the pick item
		if(args.length == 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")) && sender instanceof Player picker
				&& lastPicked.get(picker.getUniqueId()) != null && store.find(picker.getWorld().getName(), args[1]) == null)
			args = new String[] {args[0], lastPicked.get(picker.getUniqueId()), args[1]};
		Barrier b = sender instanceof Player p ? store.find(p.getWorld().getName(), args[1]) : store.get(args[1]);
		if(b == null)
			b = store.get(args[1]);
		if(b == null)
		{
			msg(sender, "No barrier named '" + args[1] + "'. Use /block list.", NamedTextColor.RED);
			return;
		}
		if(args.length < 3)
		{
			msg(sender, "'" + b.name() + "' shape copies: " + (store.hasShapes(b) ? "on" : "off") + ".", NamedTextColor.YELLOW);
			return;
		}
		String v = args[2].toLowerCase(Locale.ROOT);
		if(!v.equals("on") && !v.equals("off"))
		{
			msg(sender, "Usage: /block shapes <name> [on|off]", NamedTextColor.RED);
			return;
		}
		store.setShapes(b, v.equals("on"));
		saveStore();
		if(clientBarriers != null)
			clientBarriers.refreshVisibility();
		msg(sender, "'" + b.name() + "' shape copies " + v + (v.equals("on")
				? ": air above/below bars, fences, panes and walls copies their shape."
				: ": every cell is blocked whole, bars/fences/panes/walls included (like /block add)."), NamedTextColor.GREEN);
		if(store.getCustom(b) != null || store.getEdge(b) != null)
			msg(sender, "(It is a custom/edge barrier, so this has no effect on it.)", NamedTextColor.GRAY);
	}

	private void add(CommandSender sender, String[] args, boolean withShapes)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		if(!hasWorldEdit())
		{
			msg(sender, "FastAsyncWorldEdit or WorldEdit must be installed to use selections.", NamedTextColor.RED);
			return;
		}

		SelectionHook.Selection sel;
		try
		{
			sel = SelectionHook.read(player);
		}
		catch(Throwable t)
		{
			getLogger().log(Level.WARNING, "Could not read the WorldEdit selection", t);
			msg(sender, "Could not read your selection (see console).", NamedTextColor.RED);
			return;
		}
		if(sel == null)
		{
			msg(sender, "Select something first with //pos1, //pos2 or //wand.", NamedTextColor.RED);
			return;
		}

		String name;
		if(args.length >= 2)
		{
			name = args[1];
			if(!NAME.matcher(name).matches())
			{
				msg(sender, "Names may only use letters, numbers, _ and - (max 32).", NamedTextColor.RED);
				return;
			}
		}
		else
			name = store.nextAutoName();

		if(store.nameTakenElsewhere(name, player.getWorld().getName()))
		{
			msg(sender, "There is already a barrier named '" + name + "' in world " + store.get(name).world() + ". Pick another name.", NamedTextColor.RED);
			return;
		}
		boolean replaced = store.has(name);
		Barrier barrier = new Barrier(name, player.getWorld().getName(), sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ(), sel.footprint(), sel.volume());
		store.add(barrier);
		store.setEdge(barrier, null);
		store.setCustom(barrier, null);
		store.setShapes(barrier, withShapes);
		saveStore();
		msg(sender, (replaced ? "Replaced" : "Created") + (withShapes ? " shape" : "") + " barrier '" + name + "' at " + barrier.describe() + ".", NamedTextColor.GREEN);
		if(withShapes)
			msg(sender, "Air above/below bars, fences, panes and walls in it copies their shape.", NamedTextColor.GRAY);
		if(sel.kind() == SelectionHook.Kind.BOUNDING_BOX)
			msg(sender, "That selection is too large to store its exact shape, so its bounding box was used. Select a smaller area to follow the real shape.", NamedTextColor.YELLOW);
	}

	private static final List<String> EDIT_ACTIONS = List.of("select", "apply", "expand", "contract", "shift", "rename");
	private static final List<String> EDIT_DIRECTIONS = List.of("me", "up", "down", "north", "south", "east", "west");

	private void editHelp(CommandSender sender)
	{
		String[] lines = {
				"/block edit <name> select - put the barrier's box into your selection (then resize it with //contract, //expand, //pos1...)",
				"/block edit <name> apply - replace the barrier with your current selection, keeping its name",
				"/block edit <name> expand <amount> [direction] - grow the barrier (direction: me, up, down, north, south, east, west)",
				"/block edit <name> contract <amount> [direction] - shrink it from that side",
				"/block edit <name> shift <amount> [direction] - move it",
				"/block edit <name> rename <newName>",
				"Shaped barriers (polygon, cylinder, ...) can be renamed and replaced with apply; footprint ones can also change height (up/down)."
		};
		for(String l : lines)
			sender.sendMessage(Component.text("  " + l, NamedTextColor.GRAY));
	}

	/** A direction word as an axis step {dx, dy, dz}; "me" is where the player is facing. Null for an unknown word. */
	private static int[] direction(CommandSender sender, String word)
	{
		String w = word.toLowerCase(Locale.ROOT);
		if(w.equals("me"))
		{
			if(!(sender instanceof Player p))
				return null;
			float pitch = p.getLocation().getPitch();
			if(pitch > 60)
				return new int[] {0, -1, 0};
			if(pitch < -60)
				return new int[] {0, 1, 0};
			return switch(p.getFacing())
			{
				case NORTH -> new int[] {0, 0, -1};
				case SOUTH -> new int[] {0, 0, 1};
				case EAST -> new int[] {1, 0, 0};
				default -> new int[] {-1, 0, 0};
			};
		}
		return switch(w)
		{
			case "up", "u" -> new int[] {0, 1, 0};
			case "down", "d" -> new int[] {0, -1, 0};
			case "north", "n" -> new int[] {0, 0, -1};
			case "south", "s" -> new int[] {0, 0, 1};
			case "east", "e" -> new int[] {1, 0, 0};
			case "west", "w" -> new int[] {-1, 0, 0};
			default -> null;
		};
	}

	/**
	 * /block custom <name> <offX> <offY> <offZ> <sizeX> <sizeY> <sizeZ>: a barrier that is one exact box, decimals allowed.
	 * The offsets are from the lowest corner of the cell you selected (//pos1), or of the block you stand in without a
	 * selection; e.g. 0.4 0 0 0.2 1 1 is a 0.2-thick wall through the middle of that cell. Running it again with the same
	 * name moves/resizes it. Players collide with it on their own client (shulkers placed exactly on it).
	 */
	private void custom(CommandSender sender, String[] args)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
		if(sub.equals("cancel"))
		{
			String name = customEditor.cancel(player);
			msg(sender, name == null ? "You are not editing a custom barrier." : "Cancelled: '" + name + "' is back to how it was before you started editing.", NamedTextColor.GREEN);
			return;
		}
		if(sub.equals("mode"))
		{
			if(!customEditor.isEditing(player.getUniqueId()))
				msg(sender, "Start with /block custom first.", NamedTextColor.RED);
			else if(args.length < 3 || !customEditor.setMode(player, args[2]))
				msg(sender, "Usage: /block custom mode <single|vertical|horizontal>  (now: " + customEditor.mode(player) + ")", NamedTextColor.RED);
			else
				msg(sender, "Mode: " + customEditor.mode(player) + ".", NamedTextColor.GREEN);
			return;
		}
		if(sub.equals("done") || sub.equals("stop"))
		{
			msg(sender, customEditor.end(player) ? "Stopped editing." : "You are not editing a custom barrier.", NamedTextColor.GREEN);
			return;
		}
		if(sub.equals("grid"))
		{
			int n;
			try
			{
				n = Integer.parseInt(args.length >= 3 ? args[2] : "");
			}
			catch(NumberFormatException e)
			{
				n = -1;
			}
			if(!customEditor.isEditing(player.getUniqueId()))
				msg(sender, "Start with /block custom edit <name> first.", NamedTextColor.RED);
			else if(!customEditor.setGrid(player, n))
				msg(sender, "Usage: /block custom grid <1|2|4|8|16> (squares per block side).", NamedTextColor.RED);
			else
				msg(sender, "Grid: " + n + " per block side.", NamedTextColor.GREEN);
			return;
		}
		if(sub.isEmpty() || sub.equals("edit") || (args.length == 2 && !sub.equals("grid")))
		{
			// /block custom, /block custom edit [name], /block custom <name>
			String name = sub.equals("edit") ? (args.length >= 3 ? args[2] : store.nextAutoName()) : (args.length == 2 ? args[1] : store.nextAutoName());
			if(!NAME.matcher(name).matches())
			{
				msg(sender, "Names may only use letters, numbers, _ and - (max 32).", NamedTextColor.RED);
				return;
			}
			Barrier existing = store.find(player.getWorld().getName(), name);
			boolean converting = existing != null && store.getCustom(existing) == null;
			if(converting)
			{
				long cells = (long) (existing.maxX() - existing.minX() + 1) * (existing.maxY() - existing.minY() + 1) * (existing.maxZ() - existing.minZ() + 1);
				if(cells > CustomEditor.MAX_CONVERT_CELLS)
				{
					msg(sender, "'" + name + "' is too big to turn into a custom barrier (" + cells + " cells, max " + CustomEditor.MAX_CONVERT_CELLS + ").", NamedTextColor.RED);
					return;
				}
			}
			int[] area = customArea(player);
			customEditor.begin(player, name, area[0], area[1], area[2], area[3], area[4], area[5]);
			Barrier b = store.find(player.getWorld().getName(), name);
			if(converting)
				msg(sender, "'" + name + "' is now a custom barrier, starting from its current shape (cancel turns it back to normal).", NamedTextColor.YELLOW);
			msg(sender, "Editing custom barrier '" + name + "' at " + (b == null ? "?" : b.describe()) + " (grid " + customEditor.grid(player) + " per block).", NamedTextColor.GREEN);
			msg(sender, "  right-click = place, left-click = remove, sneak + right-click = grid size (/bb c grid <1|2|4|8|16>), sneak + left-click = single/vertical/horizontal line (/bb c mode ...).", NamedTextColor.GRAY);
			msg(sender, "  type done in chat (or /bb c done) = keep and stop, cancel (or /bb c cancel) = undo everything since you started. Hold any item to right-click at empty air.", NamedTextColor.GRAY);
			return;
		}
		if(args.length < 8)
		{
			msg(sender, "Usage: /block custom <name> <offsetX> <offsetY> <offsetZ> <sizeX> <sizeY> <sizeZ>", NamedTextColor.RED);
			msg(sender, "Offsets are from the lowest corner of the cell you selected with //pos1 (or the block you stand in). Decimals allowed, e.g. /block custom gap 0.4 0 0 0.2 1 1", NamedTextColor.GRAY);
			return;
		}
		String name = args[1];
		if(!NAME.matcher(name).matches())
		{
			msg(sender, "Names may only use letters, numbers, _ and - (max 32).", NamedTextColor.RED);
			return;
		}
		double[] v = new double[6];
		try
		{
			for(int i = 0; i < 6; i++)
				v[i] = Double.parseDouble(args[2 + i]);
		}
		catch(NumberFormatException e)
		{
			msg(sender, "Offsets and sizes must be numbers, e.g. 0.25", NamedTextColor.RED);
			return;
		}
		for(int i = 0; i < 6; i++)
			if(!Double.isFinite(v[i]) || (i >= 3 && (v[i] <= 0 || v[i] > 64)) || (i < 3 && Math.abs(v[i]) > 64))
			{
				msg(sender, "Sizes must be above 0 and at most 64; offsets between -64 and 64.", NamedTextColor.RED);
				return;
			}

		int[] cell = customCell(player);
		int cx = cell[0], cy = cell[1], cz = cell[2];
		String from = cell[3] == 1 ? "your selection" : "the block you stand in";
		double[] box = {cx + v[0], cy + v[1], cz + v[2], cx + v[0] + v[3], cy + v[1] + v[4], cz + v[2] + v[5]};
		if(store.nameTakenElsewhere(name, player.getWorld().getName()))
		{
			msg(sender, "There is already a barrier named '" + name + "' in world " + store.get(name).world() + ". Pick another name.", NamedTextColor.RED);
			return;
		}
		boolean replaced = store.has(name);
		Barrier b = new Barrier(name, player.getWorld().getName(), (int) Math.floor(box[0]), (int) Math.floor(box[1]), (int) Math.floor(box[2]),
				(int) Math.ceil(box[3]) - 1, (int) Math.ceil(box[4]) - 1, (int) Math.ceil(box[5]) - 1);
		store.add(b);
		store.setEdge(b, null);
		store.setCustom(b, List.of(box));
		saveStore();
		msg(sender, (replaced ? "Replaced" : "Created") + " custom box '" + name + "' from " + fmt3(box[0], box[1], box[2]) + " to " + fmt3(box[3], box[4], box[5])
				+ " (cell " + cx + " " + cy + " " + cz + ", from " + from + "). /block shulkers shows it.", NamedTextColor.GREEN);
	}

	/** The area for /block custom edit: the WorldEdit selection's box (pos1 + pos2), else the block the player stands in. [x0, y0, z0, x1, y1, z1] */
	private int[] customArea(Player player)
	{
		if(hasWorldEdit())
		{
			try
			{
				SelectionHook.Selection sel = SelectionHook.read(player);
				if(sel != null)
					return new int[] {sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ()};
			}
			catch(Throwable ignored)
			{
				// no usable selection: use the block the player stands in
			}
		}
		int x = player.getLocation().getBlockX(), y = player.getLocation().getBlockY(), z = player.getLocation().getBlockZ();
		return new int[] {x, y, z, x, y, z};
	}

	/** The cell for /block custom <name> <numbers>: the lowest corner of the WorldEdit selection, else the block the player stands in. [x, y, z, fromSelection] */
	private int[] customCell(Player player)
	{
		if(hasWorldEdit())
		{
			try
			{
				SelectionHook.Selection sel = SelectionHook.read(player);
				if(sel != null)
					return new int[] {sel.minX(), sel.minY(), sel.minZ(), 1};
			}
			catch(Throwable ignored)
			{
				// no usable selection: use the block the player stands in
			}
		}
		return new int[] {player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ(), 0};
	}

	private static String fmt3(double x, double y, double z)
	{
		return String.format(Locale.ROOT, "%.3f %.3f %.3f", x, y, z);
	}

	/**
	 * /block edge <name> <north|south|east|west|me|off>: turns a barrier into a thin wall (3/16 of a block) on that side of
	 * each of its cells, instead of filling the cells. "me" = the side you are looking toward. The server enforces it alone, so pressing
	 * against it can feel like a small pull-back.
	 */
	private void edge(CommandSender sender, String[] args)
	{
		if(args.length < 3)
		{
			msg(sender, "Usage: /block edge <name> <north|south|east|west|me|off>", NamedTextColor.RED);
			return;
		}
		Barrier b = sender instanceof Player p ? store.find(p.getWorld().getName(), args[1]) : null;
		if(b == null)
			b = store.get(args[1]);
		if(b == null)
		{
			msg(sender, "No barrier named '" + args[1] + "'. Use /block list.", NamedTextColor.RED);
			return;
		}
		if(store.getCustom(b) != null)
		{
			msg(sender, "'" + b.name() + "' is a custom box; an edge does not apply to it.", NamedTextColor.RED);
			return;
		}
		org.bukkit.block.BlockFace face = null;
		if(!args[2].equalsIgnoreCase("off"))
		{
			int[] d = direction(sender, args[2]);
			if(d == null || d[1] != 0)
			{
				msg(sender, "Use north, south, east, west, me (the side you look toward) or off.", NamedTextColor.RED);
				return;
			}
			face = d[0] > 0 ? org.bukkit.block.BlockFace.EAST : d[0] < 0 ? org.bukkit.block.BlockFace.WEST : d[2] > 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
		}
		store.setEdge(b, face);
		if(store.get(b.name()) == b)
			saveStore();
		msg(sender, face == null ? "Barrier '" + b.name() + "' fills its cells again."
				: "Barrier '" + b.name() + "' is now a thin wall on the " + face.name().toLowerCase(Locale.ROOT) + " side of its cells.", NamedTextColor.GREEN);
	}

	private void edit(CommandSender sender, String[] args)
	{
		if(args.length < 3)
		{
			msg(sender, "Usage: /block edit <name> <select|apply|expand|contract|shift|rename> ...", NamedTextColor.RED);
			editHelp(sender);
			return;
		}
		Barrier b = store.get(args[1]);
		if(b == null)
		{
			msg(sender, "No barrier named '" + args[1] + "'. Use /block list.", NamedTextColor.RED);
			return;
		}
		String action = args[2].toLowerCase(Locale.ROOT);
		if(store.getCustom(b) != null && !action.equals("rename"))
		{
			msg(sender, "'" + b.name() + "' is a custom box: change it by running /block custom " + b.name() + " ... again (rename works).", NamedTextColor.RED);
			return;
		}

		switch(action)
		{
			case "select" ->
			{
				if(!(sender instanceof Player player))
				{
					msg(sender, "Only players can use this.", NamedTextColor.RED);
					return;
				}
				if(!hasWorldEdit())
				{
					msg(sender, "FastAsyncWorldEdit or WorldEdit must be installed to use selections.", NamedTextColor.RED);
					return;
				}
				if(!player.getWorld().getName().equals(b.world()))
				{
					msg(sender, "That barrier is in world '" + b.world() + "'. Go there first.", NamedTextColor.RED);
					return;
				}
				try
				{
					SelectionHook.select(player, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
				}
				catch(Throwable t)
				{
					getLogger().log(Level.WARNING, "Could not set the WorldEdit selection", t);
					msg(sender, "Could not set your selection (see console).", NamedTextColor.RED);
					return;
				}
				msg(sender, "Selected the box of '" + b.name() + "'. Adjust it, then /block edit " + b.name() + " apply."
						+ (b.isShaped() ? " (It is a shaped barrier: its bounding box was selected, and apply will make it a plain box unless you re-select the shape.)" : ""), NamedTextColor.GREEN);
			}
			case "apply" ->
			{
				if(!(sender instanceof Player player))
				{
					msg(sender, "Only players can use this.", NamedTextColor.RED);
					return;
				}
				if(!hasWorldEdit())
				{
					msg(sender, "FastAsyncWorldEdit or WorldEdit must be installed to use selections.", NamedTextColor.RED);
					return;
				}
				SelectionHook.Selection sel;
				try
				{
					sel = SelectionHook.read(player);
				}
				catch(Throwable t)
				{
					getLogger().log(Level.WARNING, "Could not read the WorldEdit selection", t);
					msg(sender, "Could not read your selection (see console).", NamedTextColor.RED);
					return;
				}
				if(sel == null)
				{
					msg(sender, "Select something first with //pos1, //pos2 or //wand.", NamedTextColor.RED);
					return;
				}
				Barrier updated = new Barrier(b.name(), player.getWorld().getName(), sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ(), sel.footprint(), sel.volume());
				store.add(updated);
				saveStore();
				msg(sender, "Updated barrier '" + b.name() + "': " + updated.describe() + ".", NamedTextColor.GREEN);
			}
			case "expand", "contract", "shift" ->
			{
				if(args.length < 4)
				{
					msg(sender, "Usage: /block edit " + b.name() + " " + action + " <amount> [direction]", NamedTextColor.RED);
					return;
				}
				int amount;
				try
				{
					amount = Integer.parseInt(args[3]);
				}
				catch(NumberFormatException e)
				{
					msg(sender, "'" + args[3] + "' is not a whole number.", NamedTextColor.RED);
					return;
				}
				if(amount < 1 || amount > 512)
				{
					msg(sender, "The amount must be between 1 and 512.", NamedTextColor.RED);
					return;
				}
				int[] d = direction(sender, args.length > 4 ? args[4] : "me");
				if(d == null)
				{
					msg(sender, "Unknown direction. Use: " + String.join(", ", EDIT_DIRECTIONS) + ".", NamedTextColor.RED);
					return;
				}
				// A footprint barrier is a flat shape extruded over a height range, so only its height can change this way
				if(b.volume() != null || (b.footprint() != null && (d[0] != 0 || d[2] != 0)))
				{
					msg(sender, "This is a shaped barrier. " + (b.volume() != null ? "Use select/apply to change its shape" : "Only up/down works here; use select/apply to change its outline") + ".", NamedTextColor.RED);
					return;
				}

				int[] min = {b.minX(), b.minY(), b.minZ()};
				int[] max = {b.maxX(), b.maxY(), b.maxZ()};
				for(int axis = 0; axis < 3; axis++)
				{
					if(d[axis] == 0)
						continue;
					switch(action)
					{
						case "expand" ->
						{
							if(d[axis] > 0)
								max[axis] += amount;
							else
								min[axis] -= amount;
						}
						case "contract" ->
						{
							if(d[axis] > 0)
								max[axis] -= amount;
							else
								min[axis] += amount;
						}
						default ->
						{
							min[axis] += d[axis] * amount;
							max[axis] += d[axis] * amount;
						}
					}
				}
				if(min[0] > max[0] || min[1] > max[1] || min[2] > max[2])
				{
					msg(sender, "That would shrink the barrier away completely.", NamedTextColor.RED);
					return;
				}
				World w = Bukkit.getWorld(b.world());
				if(w != null && (min[1] < w.getMinHeight() || max[1] >= w.getMaxHeight()))
				{
					msg(sender, "That would go outside the world's height limits.", NamedTextColor.RED);
					return;
				}
				Barrier updated = new Barrier(b.name(), b.world(), min[0], min[1], min[2], max[0], max[1], max[2], b.footprint(), b.volume());
				store.add(updated);
				saveStore();
				msg(sender, "Barrier '" + b.name() + "' is now " + updated.describe() + ".", NamedTextColor.GREEN);
			}
			case "rename" ->
			{
				if(args.length < 4)
				{
					msg(sender, "Usage: /block edit " + b.name() + " rename <newName>", NamedTextColor.RED);
					return;
				}
				String newName = args[3];
				if(!NAME.matcher(newName).matches())
				{
					msg(sender, "Names may only use letters, numbers, _ and - (max 32).", NamedTextColor.RED);
					return;
				}
				if(!newName.equals(b.name()) && store.has(newName))
				{
					msg(sender, "There is already a barrier named '" + newName + "'.", NamedTextColor.RED);
					return;
				}
				org.bukkit.block.BlockFace keptEdge = store.getEdge(b);
				List<double[]> keptCustom = store.getCustom(b);
				boolean keptShapes = store.hasShapes(b);
				store.remove(b.name());
				Barrier renamed = new Barrier(newName, b.world(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.footprint(), b.volume());
				store.add(renamed);
				store.setEdge(renamed, keptEdge);
				store.setCustom(renamed, keptCustom);
				store.setShapes(renamed, keptShapes);
				saveStore();
				msg(sender, "Renamed '" + b.name() + "' to '" + newName + "'.", NamedTextColor.GREEN);
			}
			default ->
			{
				msg(sender, "Unknown edit action '" + args[2] + "'.", NamedTextColor.RED);
				editHelp(sender);
			}
		}
	}

	private void remove(CommandSender sender, String[] args)
	{
		if(args.length < 2)
		{
			msg(sender, "Usage: /block remove <name>", NamedTextColor.RED);
			return;
		}
		if(store.remove(args[1]))
		{
			saveStore();
			if(clientBarriers != null)
				clientBarriers.refreshVisibility();
			msg(sender, "Removed barrier '" + args[1] + "'.", NamedTextColor.GREEN);
		}
		else
			msg(sender, "No barrier named '" + args[1] + "'. Use /block list.", NamedTextColor.RED);
	}

	private void list(CommandSender sender)
	{
		if(store.isEmpty())
		{
			msg(sender, "No barriers yet.", NamedTextColor.YELLOW);
			return;
		}
		msg(sender, store.all().size() + " barrier(s):", NamedTextColor.YELLOW);
		for(Barrier b : store.all())
			sender.sendMessage(Component.text("  " + b.name(), NamedTextColor.AQUA).append(Component.text(" - " + b.describe(), NamedTextColor.GRAY)));
	}

	private void teleport(CommandSender sender, String[] args)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		Barrier b = args.length < 2 ? null : store.get(args[1]);
		if(b == null)
		{
			msg(sender, "Usage: /block tp <name>", NamedTextColor.RED);
			return;
		}
		World w = Bukkit.getWorld(b.world());
		if(w == null)
		{
			msg(sender, "World '" + b.world() + "' is not loaded.", NamedTextColor.RED);
			return;
		}
		// Stand just above the barrier's centre; admins' /tp is never blocked.
		player.teleport(new Location(w, (b.minX() + b.maxX() + 1) / 2.0, b.maxY() + 1.5, (b.minZ() + b.maxZ() + 1) / 2.0, player.getYaw(), player.getPitch()));
	}

	private void help(CommandSender sender, String label)
	{
		msg(sender, "Select with //pos1 and/or //pos2 (or //wand), then:", NamedTextColor.YELLOW);
		String[] lines = {
				"/" + label + " [add|a] [name] - block off your selection (plain full blocks)",
				"/" + label + " addshape|as [name] - same, but air above/below bars, fences, panes and walls copies their shape",
				"/" + label + " why - what the barrier does in the cell you look at, and why (shape, copies, MZ windows)",
				"/" + label + " shapes <name> [on|off] - switch an existing barrier between normal (off) and follow-shape (on) blocking; leave out the name for the one last picked. Or sneak + right-click it with the pick item (bone)",
				"/" + label + " remove|rm <name>",
				"/" + label + " edit|e <name> <select|apply|expand|contract|shift|rename> - change an existing barrier (see /" + label + " edit)",
				"/" + label + " edge <name> <north|south|east|west|me|off> - make a barrier a thin wall on one side of its cells",
				"/" + label + " custom [edit] [name] - build a custom barrier visually in your selection (no name = next number; right-click place, left-click remove)",
				"/" + label + " custom|cu <name> <offX> <offY> <offZ> <sizeX> <sizeY> <sizeZ> - one exact box (decimals) in the cell you selected",
				"/" + label + " list|ls",
				"/" + label + " tp|t <name>",
				"/" + label + " show|s [name...] - toggle red outlines of all barriers, or only the named ones (only you see them)",
				"/" + label + " pick|p - then right-click a barrier to see its name (cancels the click)",
				"/" + label + " info|i - name of the barrier you are looking at",
				"/" + label + " off [name] [seconds] - switch a barrier off for a while (0 = until /" + label + " on)",
				"/" + label + " on [name] - switch it back on",
				"/" + label + " clear|cl - remove all",
				"/" + label + " reload|rl - re-read data.yml"
		};
		for(String l : lines)
			sender.sendMessage(Component.text("  " + l, NamedTextColor.GRAY));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
	{
		List<String> out = new ArrayList<>();
		if(!sender.hasPermission(PERM))
			return out;
		if(args.length == 1)
		{
			for(String s : List.of("add", "addshape", "shapes", "connect", "why", "perf", "remove", "edit", "edge", "custom", "list", "tp", "show", "pick", "info", "off", "on", "debug", "shulkers", "clear", "reload"))
				if(s.startsWith(args[0].toLowerCase(Locale.ROOT)))
					out.add(s);
		}
		else if(args.length == 2 && List.of("remove", "edit", "edge", "custom", "shapes", "connect", "show", "tp", "off", "on").contains(canonical(args[0])))
		{
			// In a world with arena-instance copies, offer the barriers that exist where the player is standing.
			List<Barrier> candidates = sender instanceof Player p ? store.allBarriersIn(p.getWorld().getName()) : store.all();
			for(Barrier b : candidates)
				if(b.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
					out.add(b.name());
		}
		else if(args.length == 2 && canonical(args[0]).equals("custom"))
		{
			for(String w : List.of("edit", "done", "cancel", "grid", "mode"))
				if(w.startsWith(args[1].toLowerCase(Locale.ROOT)))
					out.add(w);
			if(sender instanceof Player p)
				for(Barrier b : store.allBarriersIn(p.getWorld().getName()))
					if(b.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
						out.add(b.name());
		}
		else if(args.length == 3 && canonical(args[0]).equals("custom") && args[1].equalsIgnoreCase("edit") && sender instanceof Player p)
		{
			for(Barrier b : store.allBarriersIn(p.getWorld().getName()))
				if(b.name().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
					out.add(b.name());
		}
		else if(args.length == 3 && canonical(args[0]).equals("custom") && args[1].equalsIgnoreCase("mode"))
		{
			for(String m : CustomEditor.MODES)
				if(m.startsWith(args[2].toLowerCase(Locale.ROOT)))
					out.add(m);
		}
		else if(args.length == 3 && canonical(args[0]).equals("custom") && args[1].equalsIgnoreCase("grid"))
		{
			for(String n : List.of("1", "2", "4", "8", "16"))
				if(n.startsWith(args[2]))
					out.add(n);
		}
		else if(args.length == 3 && canonical(args[0]).equals("shapes"))
		{
			for(String v : List.of("on", "off"))
				if(v.startsWith(args[2].toLowerCase(Locale.ROOT)))
					out.add(v);
		}
		else if(args.length == 3 && canonical(args[0]).equals("edge"))
		{
			for(String d : List.of("north", "south", "east", "west", "me", "off"))
				if(d.startsWith(args[2].toLowerCase(Locale.ROOT)))
					out.add(d);
		}
		else if(args.length == 3 && canonical(args[0]).equals("edit"))
		{
			for(String a : EDIT_ACTIONS)
				if(a.startsWith(args[2].toLowerCase(Locale.ROOT)))
					out.add(a);
		}
		else if(args.length == 5 && canonical(args[0]).equals("edit"))
		{
			for(String dir : EDIT_DIRECTIONS)
				if(dir.startsWith(args[4].toLowerCase(Locale.ROOT)))
					out.add(dir);
		}
		return out;
	}
}
