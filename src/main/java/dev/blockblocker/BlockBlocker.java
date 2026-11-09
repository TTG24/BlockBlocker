package dev.blockblocker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class BlockBlocker extends JavaPlugin implements TabExecutor, Listener
{
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final String PERM = "blockblocker.admin";

	private BarrierStore store;
	private ClientBarriers clientBarriers;

	private boolean collidersWanted;
	private boolean respectPartial;

	@Override
	public void onLoad()
	{
		saveDefaultConfig();
		// Experimental: needs a restart (not /block reload) to change, because PacketEvents starts with the server.
		collidersWanted = getConfig().getBoolean("shulker-colliders", false) && ColliderPackets.load(this);
	}

	@Override
	public void onDisable()
	{
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
		getServer().getPluginManager().registerEvents(new BarrierListener(store), this);
		getServer().getPluginManager().registerEvents(this, this);
		clientBarriers = new ClientBarriers(this, store, colliders);
		clientBarriers.start();
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
	private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.RED, 1.2f);
	private final Set<UUID> viewers = new HashSet<>();

	private void toggleShow(CommandSender sender)
	{
		if(!(sender instanceof Player player))
		{
			msg(sender, "Only players can use this.", NamedTextColor.RED);
			return;
		}
		if(viewers.remove(player.getUniqueId()))
			msg(sender, "Barrier outlines hidden.", NamedTextColor.YELLOW);
		else
		{
			viewers.add(player.getUniqueId());
			msg(sender, "Showing barrier outlines (red) within " + (int) SHOW_RANGE + " blocks. Run /block show again to hide.", NamedTextColor.GREEN);
		}
	}

	private void drawOutlines()
	{
		if(viewers.isEmpty())
			return;
		viewers.removeIf(id -> Bukkit.getPlayer(id) == null);
		for(UUID id : viewers)
		{
			Player p = Bukkit.getPlayer(id);
			if(p == null)
				continue;
			Location loc = p.getLocation();
			for(Barrier b : store.barriersIn(p.getWorld().getName()))
			{
				double x0 = b.minX(), y0 = b.minY(), z0 = b.minZ(), x1 = b.maxX() + 1.0, y1 = b.maxY() + 1.0, z1 = b.maxZ() + 1.0;
				// Skip barriers entirely out of range.
				if(loc.getX() < x0 - SHOW_RANGE || loc.getX() > x1 + SHOW_RANGE || loc.getZ() < z0 - SHOW_RANGE || loc.getZ() > z1 + SHOW_RANGE
						|| loc.getY() < y0 - SHOW_RANGE || loc.getY() > y1 + SHOW_RANGE)
					continue;
				if(b.footprint() != null)
				{
					footprintOutline(p, b);
					continue;
				}
				for(double y : new double[]{y0, y1})
					for(double z : new double[]{z0, z1})
						edge(p, x0, y, z, x1, y, z);
				for(double x : new double[]{x0, x1})
					for(double z : new double[]{z0, z1})
						edge(p, x, y0, z, x, y1, z);
				for(double x : new double[]{x0, x1})
					for(double y : new double[]{y0, y1})
						edge(p, x, y, z0, x, y, z1);
			}
		}
	}

	/** Outlines a shaped barrier: its outer wall edges at the bottom and top, plus a vertical line at each wall corner. */
	private void footprintOutline(Player p, Barrier b)
	{
		Footprint fp = b.footprint();
		Location l = p.getLocation();
		int px = l.getBlockX(), pz = l.getBlockZ(), r = (int) SHOW_RANGE;
		double y0 = b.minY(), y1 = b.maxY() + 1.0;
		for(int x = Math.max(b.minX(), px - r); x <= Math.min(b.maxX(), px + r); x++)
			for(int z = Math.max(b.minZ(), pz - r); z <= Math.min(b.maxZ(), pz + r); z++)
			{
				if(!fp.contains(x, z))
					continue;
				if(!fp.contains(x, z - 1)) // wall on the north side
					wallEdge(p, x, z, x + 1, z, y0, y1);
				if(!fp.contains(x, z + 1)) // south
					wallEdge(p, x, z + 1, x + 1, z + 1, y0, y1);
				if(!fp.contains(x - 1, z)) // west
					wallEdge(p, x, z, x, z + 1, y0, y1);
				if(!fp.contains(x + 1, z)) // east
					wallEdge(p, x + 1, z, x + 1, z + 1, y0, y1);
			}
	}

	private void wallEdge(Player p, double xa, double za, double xb, double zb, double y0, double y1)
	{
		edge(p, xa, y0, za, xb, y0, zb);
		edge(p, xa, y1, za, xb, y1, zb);
		edge(p, xa, y0, za, xa, y1, za);
	}

	/** Particles along one axis-aligned edge, only the part near the viewer. */
	private void edge(Player p, double x0, double y0, double z0, double x1, double y1, double z1)
	{
		Location l = p.getLocation();
		double len = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
		double step = 0.5;
		double rangeSq = SHOW_RANGE * SHOW_RANGE;
		for(double t = 0; t <= len + 1.0E-9; t += step)
		{
			double x = x0 + Math.min(t, x1 - x0), y = y0 + Math.min(t, y1 - y0), z = z0 + Math.min(t, z1 - z0);
			double dx = x - l.getX(), dy = y - l.getY(), dz = z - l.getZ();
			if(dx * dx + dy * dy + dz * dz <= rangeSq)
				p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, DUST);
		}
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

		String sub = args.length == 0 ? "add" : args[0].toLowerCase(Locale.ROOT);
		switch(sub)
		{
			case "add", "create", "set" -> add(sender, args);
			case "remove", "delete", "del" -> remove(sender, args);
			case "list" -> list(sender);
			case "tp", "goto" -> teleport(sender, args);
			case "show" -> toggleShow(sender);
			case "clear" ->
			{
				int n = store.clear();
				saveStore();
				msg(sender, "Removed " + n + " barrier(s).", NamedTextColor.GREEN);
			}
			case "reload" ->
			{
				reloadConfig();
				respectPartial = !collidersWanted && getConfig().getBoolean("respect-partial-blocks", true);
				store.setRespectPartial(respectPartial);
				store.load();
				msg(sender, "Reloaded " + store.all().size() + " barrier(s).", NamedTextColor.GREEN);
			}
			default -> help(sender, label);
		}
		return true;
	}

	private void add(CommandSender sender, String[] args)
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

		boolean replaced = store.has(name);
		Barrier barrier = new Barrier(name, player.getWorld().getName(), sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ(), sel.footprint());
		store.add(barrier);
		saveStore();
		msg(sender, (replaced ? "Replaced" : "Created") + " barrier '" + name + "' at " + barrier.describe() + ".", NamedTextColor.GREEN);
		if(sel.kind() == SelectionHook.Kind.BOUNDING_BOX)
			msg(sender, "That selection shape isn't supported, so its bounding box was used. Cuboid, polygon and cylinder follow their real shape.", NamedTextColor.YELLOW);
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
				"/" + label + " [add] [name] - block off your selection",
				"/" + label + " remove <name>",
				"/" + label + " list",
				"/" + label + " tp <name>",
				"/" + label + " show - toggle red outlines of all barriers (only you see them)",
				"/" + label + " clear - remove all",
				"/" + label + " reload - re-read data.yml"
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
			for(String s : List.of("add", "remove", "list", "tp", "show", "clear", "reload"))
				if(s.startsWith(args[0].toLowerCase(Locale.ROOT)))
					out.add(s);
		}
		else if(args.length == 2 && List.of("remove", "delete", "del", "tp", "goto").contains(args[0].toLowerCase(Locale.ROOT)))
		{
			for(Barrier b : store.all())
				if(b.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
					out.add(b.name());
		}
		return out;
	}
}
