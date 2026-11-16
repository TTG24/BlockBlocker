package dev.blockblocker;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Visual editor for custom barriers ({@code /block custom [edit] [name]}): the selected area of cells, shown to the
 * editing player with straight outlines and a grid on the surface looked at (see LineView). Right-click places a cube one grid square in
 * size (against the surface you look at, like placing blocks), left-click removes the cube you look at, sneak + right-click
 * changes the grid size. Every change is saved at once, and the cubes are real custom-barrier boxes, so players collide with
 * them straight away.
 */
final class CustomEditor implements Listener
{
	private static final double REACH = 6;
	private static final int[] DIVISIONS = {1, 2, 4, 8, 16};
	static final String[] MODES = {"single", "vertical", "horizontal"};
	private static final long CLICK_COOLDOWN_MS = 120;
	/** After a click on one of the cubes (an invisible shulker), the swing/use packet of the same click is ignored. */
	private static final long ENTITY_CLICK_WINDOW_MS = 250;

	/** One player's editing session: an area of whole cells (the selection), inclusive. */
	private static final class Session
	{
		final String world;
		final String name;
		final int x0, y0, z0, x1, y1, z1;
		int div = 4;
		/** 0 = one cube per click, 1 = a vertical line, 2 = a horizontal line (across the whole area). */
		int mode;
		long lastClick;
		/** The boxes when editing started (for cancel), or null if this barrier was created by this session. */
		List<double[]> original;
		/** True if this was a normal (or edge) barrier turned custom by this session; cancel turns it back. */
		boolean convertedFrom;
		org.bukkit.block.BlockFace originalEdge;

		Session(String world, String name, int x0, int y0, int z0, int x1, int y1, int z1)
		{
			this.world = world;
			this.name = name;
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			this.x1 = x1;
			this.y1 = y1;
			this.z1 = z1;
		}

		BoundingBox area()
		{
			return new BoundingBox(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		}
	}

	/**
	 * The surface looked at: where it was hit, its normal (pointing toward the player), and whether the cell a new cube goes
	 * into lies behind it (the area's own outside faces) or in front of it (a cube or a real block).
	 */
	private record Face(Vector hit, Vector normal, boolean cellBehind)
	{
		/** A point just inside the cell a new cube would go into. */
		Vector target()
		{
			return hit.clone().add(normal.clone().multiply(cellBehind ? -1e-4 : 1e-4));
		}
	}

	/** What the player points at: the surface (for the grid), the cube to remove (if any), where a new cube goes (if anywhere). */
	private record Aim(Face face, List<double[]> remove, List<double[]> place)
	{
	}

	private final JavaPlugin plugin;
	private final BarrierStore store;
	private final Runnable save;
	private final Supplier<ClientBarriers> client;
	private final Map<UUID, Session> sessions = new HashMap<>();
	/** When each player last clicked one of the cubes directly. Written from the network thread. */
	private final Map<UUID, Long> entityClicks = new ConcurrentHashMap<>();

	CustomEditor(JavaPlugin plugin, BarrierStore store, Runnable save, Supplier<ClientBarriers> client)
	{
		this.plugin = plugin;
		this.store = store;
		this.save = save;
		this.client = client;
	}

	void start()
	{
		Bukkit.getPluginManager().registerEvents(this, plugin);
		// Lines only change when what is looked at changes, so a quick redraw costs little and feels responsive.
		Bukkit.getScheduler().runTaskTimer(plugin, this::draw, 10L, 2L);
	}

	/** Safe to call from any thread (it is only ever changed on the main thread, and a stale answer is harmless). */
	boolean isEditing(UUID player)
	{
		return sessions.containsKey(player);
	}

	/**
	 * Starts editing the area of cells (x0..x1, y0..y1, z0..z1, inclusive) as custom barrier {@code name}, created empty if
	 * it doesn't exist yet. An existing custom barrier is edited in its own area.
	 */
	void begin(Player player, String name, int x0, int y0, int z0, int x1, int y1, int z1)
	{
		String world = player.getWorld().getName();
		Barrier b = store.find(world, name);
		List<double[]> original = b == null ? null : store.getCustom(b);
		if(b != null && original == null)
		{
			// An already made (normal or edge) barrier: start from the exact shape it has now, so it can be fine-tuned.
			Session s = new Session(world, name, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
			s.original = List.of();
			s.convertedFrom = true;
			s.originalEdge = store.getEdge(b);
			store.setCustom(b, merge(shapeOf(player.getWorld(), b)));
			store.setEdge(b, null);
			save.run();
			put(player, s);
			return;
		}
		if(b == null)
		{
			b = new Barrier(name, world, x0, y0, z0, x1, y1, z1);
			store.add(b);
			store.setEdge(b, null);
			store.setCustom(b, new ArrayList<>());
			save.run();
			original = null;
		}
		Session s = new Session(world, name, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
		s.original = original;
		if(original != null && !original.isEmpty())
		{
			// Join up boxes saved before boxes were merged (fewer shulkers and outlines); cancel still restores the original.
			List<double[]> merged = merge(original);
			if(merged.size() < original.size())
			{
				store.setCustom(b, merged);
				save.run();
			}
		}
		put(player, s);
	}

	private void put(Player player, Session s)
	{
		Session old = sessions.put(player.getUniqueId(), s);
		if(old != null)
		{
			s.div = old.div;
			s.mode = old.mode;
		}
	}

	/** Most cells an already made barrier may have to be turned into a custom one. */
	static final int MAX_CONVERT_CELLS = 4096;

	/**
	 * The exact solid shape a barrier has right now (what the server enforces), as custom boxes, one per cell piece.
	 */
	private List<double[]> shapeOf(World world, Barrier b)
	{
		List<double[]> out = new ArrayList<>();
		for(int y = b.minY(); y <= b.maxY(); y++)
			for(int z = b.minZ(); z <= b.maxZ(); z++)
				for(int x = b.minX(); x <= b.maxX(); x++)
				{
					if(!b.contains(x, y, z))
						continue;
					// One box per piece per cell (not joined across cells), so the editor removes them one cell at a time.
					for(BoundingBox box : store.cellBoxes(world, b, x, y, z))
						out.add(new double[] {box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()});
				}
		return out;
	}

	/** @return false if the player wasn't editing. */
	boolean end(Player player)
	{
		return sessions.remove(player.getUniqueId()) != null;
	}

	/**
	 * Stops editing and throws away every change made since editing started: the boxes go back to how they were, and a
	 * barrier this session created is removed. @return the barrier name, or null if the player wasn't editing.
	 */
	String cancel(Player player)
	{
		Session s = sessions.remove(player.getUniqueId());
		if(s == null)
			return null;
		Barrier b = store.find(s.world, s.name);
		if(b != null)
		{
			if(s.convertedFrom)
			{
				// Was a normal (or edge) barrier before: make it one again.
				store.setCustom(b, null);
				store.setEdge(b, s.originalEdge);
			}
			else if(s.original == null)
				store.remove(s.name);
			else
				store.setCustom(b, s.original);
			save.run();
		}
		return s.name;
	}

	/** Sets the click mode (single, vertical, horizontal). @return false for another word. */
	boolean setMode(Player player, String word)
	{
		Session s = sessions.get(player.getUniqueId());
		if(s == null)
			return false;
		for(int i = 0; i < MODES.length; i++)
			if(MODES[i].equalsIgnoreCase(word))
			{
				s.mode = i;
				return true;
			}
		return false;
	}

	String mode(Player player)
	{
		Session s = sessions.get(player.getUniqueId());
		return s == null ? MODES[0] : MODES[s.mode];
	}

	/** Sets the grid to n squares per block side (1, 2, 4, 8 or 16). @return false for another number. */
	boolean setGrid(Player player, int n)
	{
		Session s = sessions.get(player.getUniqueId());
		if(s == null)
			return false;
		for(int d : DIVISIONS)
			if(d == n)
			{
				s.div = n;
				return true;
			}
		return false;
	}

	int grid(Player player)
	{
		Session s = sessions.get(player.getUniqueId());
		return s == null ? 0 : s.div;
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event)
	{
		sessions.remove(event.getPlayer().getUniqueId());
		entityClicks.remove(event.getPlayer().getUniqueId());
		LineView v = views.remove(event.getPlayer().getUniqueId());
		if(v != null)
			v.clear();
	}

	/**
	 * Typing "done" (keep and stop) or "cancel" (undo everything since starting) in chat while editing, like the
	 * MinecraftZombies setup. The message is not sent to anyone. Chat runs off the main thread, so the work is handed to it.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onChat(io.papermc.paper.event.player.AsyncChatEvent event)
	{
		Player player = event.getPlayer();
		if(!isEditing(player.getUniqueId()))
			return;
		String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
		boolean done = text.equalsIgnoreCase("done"), cancel = text.equalsIgnoreCase("cancel");
		if(!done && !cancel)
			return;
		event.setCancelled(true);
		Bukkit.getScheduler().runTask(plugin, () ->
		{
			if(done)
			{
				Session s = sessions.remove(player.getUniqueId());
				if(s != null)
					player.sendMessage(net.kyori.adventure.text.Component.text("Finished editing '" + s.name + "'.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
			}
			else
			{
				String name = cancel(player);
				if(name != null)
					player.sendMessage(net.kyori.adventure.text.Component.text("Cancelled: '" + name + "' is back to how it was before you started editing.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
			}
		});
	}

	/** Right-clicks (and any block use) of an editing player only edit; nothing else happens. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteract(PlayerInteractEvent event)
	{
		if(!isEditing(event.getPlayer().getUniqueId()))
			return;
		event.setCancelled(true);
		if(event.getHand() != EquipmentSlot.HAND)
			return;
		if(event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
			click(event.getPlayer(), true);
	}

	/** An editing player in creative must not break the blocks around the area while left-clicking. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBreak(BlockBreakEvent event)
	{
		if(isEditing(event.getPlayer().getUniqueId()))
			event.setCancelled(true);
	}

	/**
	 * The player's client says it clicked one of our invisible shulkers (network thread). This is exact, unlike aiming
	 * from the server's copy of the player's rotation, so it wins over the swing/use packet of the same click.
	 */
	void noteEntityClick(Player player)
	{
		entityClicks.put(player.getUniqueId(), System.currentTimeMillis());
	}

	/** A click on a cube's own shulker (main thread): left removes exactly that cube, right places against it. */
	void clickEntity(Player player, boolean right, int entityId)
	{
		Session s = sessions.get(player.getUniqueId());
		ClientBarriers cb = client.get();
		double[] cube = cb == null ? null : cb.cubeOf(player.getUniqueId(), entityId);
		if(s == null || cube == null)
		{
			click(player, right, true);
			return;
		}
		Barrier b = store.find(s.world, s.name);
		List<double[]> boxes = b == null ? null : store.getCustom(b);
		if(boxes == null)
			return;
		// The custom box this shulker belongs to (it lies inside it).
		double cx = (cube[0] + cube[3]) / 2, cy = (cube[1] + cube[4]) / 2, cz = (cube[2] + cube[5]) / 2;
		double[] box = null;
		for(double[] o : boxes)
			if(cx >= o[0] && cx <= o[3] && cy >= o[1] && cy <= o[4] && cz >= o[2] && cz <= o[5])
				box = o;
		if(box == null || player.isSneaking())
		{
			click(player, right, true);
			return;
		}
		if(!cooldownPassed(s))
			return;
		Location look = player.getEyeLocation();
		if(!right)
		{
			// Cut out the grid square of this shulker's cube (its centre lies inside the box).
			RayTraceResult hitFace = toBox(box).expand(0.05).rayTrace(look.toVector(), look.getDirection(), REACH + 1);
			Vector n = hitFace != null && hitFace.getHitBlockFace() != null ? hitFace.getHitBlockFace().getDirection() : facing(look.getDirection());
			Vector at = hitFace != null ? hitFace.getHitPosition() : new Vector(cx, cy, cz);
			at = new Vector(Math.max(box[0], Math.min(box[3], at.getX())), Math.max(box[1], Math.min(box[4], at.getY())), Math.max(box[2], Math.min(box[5], at.getZ())));
			apply(player, s, b, boxes, List.of(), cuts(s, box, at, n, lineAxis(s, n, look.getDirection()), boxes));
			return;
		}
		// Place against the face of that box the player looks at.
		Location eye = player.getEyeLocation();
		RayTraceResult r = toBox(box).expand(0.05).rayTrace(eye.toVector(), eye.getDirection(), REACH + 1);
		Vector normal = r != null && r.getHitBlockFace() != null ? r.getHitBlockFace().getDirection() : facing(eye.getDirection());
		Vector hit = r != null ? r.getHitPosition()
				: new Vector(cx, cy, cz).add(normal.clone().multiply(Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])) / 2));
		// Clamp the point onto the box itself, then step just outside the face.
		hit = new Vector(Math.max(box[0], Math.min(box[3], hit.getX())), Math.max(box[1], Math.min(box[4], hit.getY())), Math.max(box[2], Math.min(box[5], hit.getZ())));
		double[] place = snap(s, new Face(hit, normal, false).target(), 1.0 / s.div);
		if(place != null)
			apply(player, s, b, boxes, line(s, place, lineAxis(s, normal, eye.getDirection()), boxes), List.of());
	}

	void click(Player player, boolean right)
	{
		click(player, right, false);
	}

	/**
	 * A click of an editing player. right = place (or, sneaking, change the grid size), left = remove. Main thread only.
	 *
	 * @param fromEntity true if this is the fallback of a click on a shulker (so the recent-shulker-click check is skipped)
	 */
	private void click(Player player, boolean right, boolean fromEntity)
	{
		Session s = sessions.get(player.getUniqueId());
		if(s == null || !player.getWorld().getName().equals(s.world))
			return;
		if(!fromEntity)
		{
			Long last = entityClicks.get(player.getUniqueId());
			if(last != null && System.currentTimeMillis() - last < ENTITY_CLICK_WINDOW_MS)
				return; // the same click also hit a shulker: that exact version is handled instead
		}
		if(!cooldownPassed(s))
			return;

		Barrier b = store.find(s.world, s.name);
		List<double[]> boxes = b == null ? null : store.getCustom(b);
		if(boxes == null)
		{
			sessions.remove(player.getUniqueId());
			player.sendActionBar(net.kyori.adventure.text.Component.text("That custom barrier no longer exists; editing stopped."));
			return;
		}

		if(right && player.isSneaking())
		{
			int i = 0;
			while(i < DIVISIONS.length && DIVISIONS[i] != s.div)
				i++;
			s.div = DIVISIONS[(i + 1) % DIVISIONS.length];
			player.sendActionBar(net.kyori.adventure.text.Component.text("Grid: " + s.div + " per block (cube " + fmt(1.0 / s.div) + ")"));
			return;
		}
		if(!right && player.isSneaking())
		{
			s.mode = (s.mode + 1) % MODES.length;
			player.sendActionBar(net.kyori.adventure.text.Component.text("Mode: " + MODES[s.mode] + (s.mode == 0 ? " (one cube per click)" : " line (one click fills the whole line)")));
			return;
		}

		Aim aim = aim(player, s, boxes);
		if(right && !aim.place().isEmpty())
			apply(player, s, b, boxes, aim.place(), List.of());
		else if(!right && !aim.remove().isEmpty())
			apply(player, s, b, boxes, List.of(), aim.remove());
	}

	private boolean cooldownPassed(Session s)
	{
		long now = System.currentTimeMillis();
		if(now - s.lastClick < CLICK_COOLDOWN_MS)
			return false; // one click can arrive more than once
		s.lastClick = now;
		return true;
	}

	/** Adds boxes and cuts regions out, then joins touching boxes that line up, so lines are stored (and sent) as one box. */
	private void apply(Player player, Session s, Barrier b, List<double[]> boxes, List<double[]> add, List<double[]> cut)
	{
		if(add.isEmpty() && cut.isEmpty())
			return;
		List<double[]> next = new ArrayList<>(boxes);
		for(double[] c : cut)
			next = subtractAll(next, c);
		next.addAll(add);
		next = merge(next);
		store.setCustom(b, next);
		save.run();
		player.sendActionBar(net.kyori.adventure.text.Component.text("'" + s.name + "': " + next.size() + " box(es)"));
	}

	private static final double EPS = 1e-6;

	private static boolean same(double a, double b)
	{
		return Math.abs(a - b) < EPS;
	}

	/** The overlap of two boxes, or null if they don't overlap (touching doesn't count). */
	static double[] intersect(double[] a, double[] b)
	{
		double[] r = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]), Math.min(a[3], b[3]), Math.min(a[4], b[4]), Math.min(a[5], b[5])};
		return r[3] - r[0] > EPS && r[4] - r[1] > EPS && r[5] - r[2] > EPS ? r : null;
	}

	/** box minus cut: up to 6 boxes. */
	static List<double[]> subtract(double[] box, double[] cut)
	{
		double[] in = intersect(box, cut);
		if(in == null)
			return List.of(box);
		List<double[]> out = new ArrayList<>(6);
		double[] rest = box.clone();
		for(int axis = 0; axis < 3; axis++)
		{
			if(in[axis] - rest[axis] > EPS)
			{
				double[] piece = rest.clone();
				piece[axis + 3] = in[axis];
				out.add(piece);
			}
			if(rest[axis + 3] - in[axis + 3] > EPS)
			{
				double[] piece = rest.clone();
				piece[axis] = in[axis + 3];
				out.add(piece);
			}
			rest[axis] = in[axis];
			rest[axis + 3] = in[axis + 3];
		}
		return out;
	}

	static List<double[]> subtractAll(List<double[]> boxes, double[] cut)
	{
		List<double[]> out = new ArrayList<>(boxes.size() + 4);
		for(double[] box : boxes)
			out.addAll(subtract(box, cut));
		return out;
	}

	/** Two boxes that are the same on two axes and touch on the third make one box: their union. Else null. */
	private static double[] union(double[] a, double[] b)
	{
		for(int axis = 0; axis < 3; axis++)
		{
			int o1 = (axis + 1) % 3, o2 = (axis + 2) % 3;
			if(same(a[o1], b[o1]) && same(a[o1 + 3], b[o1 + 3]) && same(a[o2], b[o2]) && same(a[o2 + 3], b[o2 + 3])
					&& a[axis] <= b[axis + 3] + EPS && b[axis] <= a[axis + 3] + EPS)
			{
				double[] u = a.clone();
				u[axis] = Math.min(a[axis], b[axis]);
				u[axis + 3] = Math.max(a[axis + 3], b[axis + 3]);
				return u;
			}
		}
		return null;
	}

	/** Joins boxes that line up and touch, over and over, until none can be joined. */
	static List<double[]> merge(List<double[]> boxes)
	{
		List<double[]> list = new ArrayList<>(boxes);
		java.util.ArrayDeque<double[]> work = new java.util.ArrayDeque<>(list);
		while(!work.isEmpty())
		{
			double[] w = work.poll();
			int wi = indexOfIdentity(list, w);
			if(wi < 0)
				continue; // already joined into another box
			for(int i = 0; i < list.size(); i++)
			{
				if(i == wi)
					continue;
				double[] u = union(w, list.get(i));
				if(u != null)
				{
					double[] other = list.get(i);
					list.remove(Math.max(i, wi));
					list.remove(Math.min(i, wi));
					list.add(u);
					work.remove(other);
					work.add(u);
					break;
				}
			}
		}
		return list;
	}

	private static int indexOfIdentity(List<double[]> list, double[] box)
	{
		for(int i = 0; i < list.size(); i++)
			if(list.get(i) == box)
				return i;
		return -1;
	}

	/**
	 * Works out what the player points at: the nearest of an existing cube, an outside face of the area, or a real block.
	 * A cube wins a tie, so a cube lying on the area's face can be removed or built on.
	 */
	private Aim aim(Player player, Session s, List<double[]> boxes)
	{
		Location eye = player.getEyeLocation();
		Vector start = eye.toVector(), dir = eye.getDirection();

		double[] cubeBox = null;
		RayTraceResult cubeHit = null;
		for(double[] box : boxes)
		{
			RayTraceResult r = toBox(box).rayTrace(start, dir, REACH);
			if(r != null && r.getHitBlockFace() != null && (cubeHit == null || dist(r, start) < dist(cubeHit, start)))
			{
				cubeHit = r;
				cubeBox = box;
			}
		}
		RayTraceResult areaHit = s.area().contains(start) ? null : s.area().rayTrace(start, dir, REACH);
		if(areaHit != null && areaHit.getHitBlockFace() == null)
			areaHit = null;
		RayTraceResult blockHit = player.getWorld().rayTraceBlocks(eye, dir, REACH, FluidCollisionMode.NEVER, true);
		if(blockHit != null && blockHit.getHitBlockFace() == null)
			blockHit = null;

		double dCube = cubeHit == null ? Double.MAX_VALUE : dist(cubeHit, start);
		double dArea = areaHit == null ? Double.MAX_VALUE : dist(areaHit, start);
		double dBlock = blockHit == null ? Double.MAX_VALUE : dist(blockHit, start);

		Face face = null;
		double[] remove = null;
		Vector removeAt = null;
		if(cubeHit != null && dCube <= dArea + 1e-6 && dCube <= dBlock + 1e-6)
		{
			face = new Face(cubeHit.getHitPosition(), cubeHit.getHitBlockFace().getDirection(), false);
			remove = cubeBox;
			removeAt = cubeHit.getHitPosition();
		}
		else if(areaHit != null && dArea <= dBlock + 1e-6)
			face = new Face(areaHit.getHitPosition(), areaHit.getHitBlockFace().getDirection(), true);
		else if(blockHit != null)
			face = new Face(blockHit.getHitPosition(), blockHit.getHitBlockFace().getDirection(), false);

		double[] square = face == null ? null : snap(s, face.target(), 1.0 / s.div);
		int axis = face == null ? 1 : lineAxis(s, face.normal(), dir);
		List<double[]> place = square == null ? List.of() : merge(line(s, square, axis, boxes));
		List<double[]> removeAll = remove == null ? List.of() : cuts(s, remove, removeAt, face.normal(), axis, boxes);
		return new Aim(face, removeAll, place);
	}

	/**
	 * The axis a line runs along: vertical = up; horizontal = along the face looked at, or (on a floor/ceiling) the way
	 * the player looks. Single mode: unused.
	 */
	private static int lineAxis(Session s, Vector normal, Vector look)
	{
		if(s.mode == 1)
			return 1;
		if(Math.abs(normal.getX()) > 0.5)
			return 2;
		if(Math.abs(normal.getZ()) > 0.5)
			return 0;
		return Math.abs(look.getX()) >= Math.abs(look.getZ()) ? 0 : 2;
	}

	/** The cubes to place: one square, or (line modes) every free square of the line through it across the whole area. */
	private static List<double[]> line(Session s, double[] square, int axis, List<double[]> boxes)
	{
		if(s.mode == 0)
			return overlapsAny(square, boxes) ? List.of() : List.of(square);
		double g = square[3] - square[0];
		double lo = axis == 0 ? s.x0 : axis == 1 ? s.y0 : s.z0, hi = (axis == 0 ? s.x1 : axis == 1 ? s.y1 : s.z1) + 1;
		List<double[]> out = new ArrayList<>();
		for(double v = lo; v + g <= hi + 1e-9; v += g)
		{
			double[] box = square.clone();
			box[axis] = v;
			box[axis + 3] = v + g;
			if(!overlapsAny(box, boxes))
				out.add(box);
		}
		return out;
	}

	/**
	 * What a left-click cuts away: the grid square at the point looked at, out of the box looked at (single mode), or
	 * (line modes) that square's whole line across the area, out of every box on it. Boxes are cut, not removed whole, so
	 * one piece of a joined-up line can be taken out.
	 */
	private static List<double[]> cuts(Session s, double[] hitBox, Vector hitPoint, Vector normal, int axis, List<double[]> boxes)
	{
		double g = 1.0 / s.div;
		Vector inside = hitPoint.clone().subtract(normal.clone().multiply(1e-4));
		double x = Math.floor(inside.getX() / g) * g, y = Math.floor(inside.getY() / g) * g, z = Math.floor(inside.getZ() / g) * g;
		double[] square = {x, y, z, x + g, y + g, z + g};
		if(s.mode == 0)
		{
			double[] in = intersect(square, hitBox);
			return in == null ? List.of(hitBox) : List.of(in);
		}
		double[] lineBox = square.clone();
		lineBox[axis] = axis == 0 ? s.x0 : axis == 1 ? s.y0 : s.z0;
		lineBox[axis + 3] = (axis == 0 ? s.x1 : axis == 1 ? s.y1 : s.z1) + 1;
		List<double[]> out = new ArrayList<>();
		for(double[] o : boxes)
		{
			double[] in = intersect(lineBox, o);
			if(in != null)
				out.add(in);
		}
		return merge(out);
	}

	private static double dist(RayTraceResult r, Vector start)
	{
		return r.getHitPosition().distance(start);
	}

	/** The axis direction closest to the opposite of the look direction (the face turned toward the player). */
	private static Vector facing(Vector dir)
	{
		double ax = Math.abs(dir.getX()), ay = Math.abs(dir.getY()), az = Math.abs(dir.getZ());
		if(ax >= ay && ax >= az)
			return new Vector(-Math.signum(dir.getX()), 0, 0);
		if(ay >= az)
			return new Vector(0, -Math.signum(dir.getY()), 0);
		return new Vector(0, 0, -Math.signum(dir.getZ()));
	}

	/** The grid square of size g containing p (the grid lines up with every block edge), if it lies inside the area. */
	private static double[] snap(Session s, Vector p, double g)
	{
		double x = Math.floor(p.getX() / g) * g, y = Math.floor(p.getY() / g) * g, z = Math.floor(p.getZ() / g) * g;
		if(x < s.x0 || y < s.y0 || z < s.z0 || x + g > s.x1 + 1 + 1e-9 || y + g > s.y1 + 1 + 1e-9 || z + g > s.z1 + 1 + 1e-9)
			return null;
		return new double[] {x, y, z, x + g, y + g, z + g};
	}

	private static boolean overlapsAny(double[] box, List<double[]> boxes)
	{
		BoundingBox b = toBox(box);
		for(double[] o : boxes)
			if(toBox(o).overlaps(b))
				return true;
		return false;
	}

	private static BoundingBox toBox(double[] b)
	{
		return new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
	}

	// -------------------------------------------------------------------------------------------------------------
	// Drawing (particles only the editing player sees)
	// -------------------------------------------------------------------------------------------------------------

	private void draw()
	{
		if(sessions.isEmpty() && views.isEmpty())
			return;
		long t0 = System.nanoTime();
		try
		{
			drawInner();
		}
		finally
		{
			Perf.add(Perf.EDITOR, t0);
		}
	}

	private void drawInner()
	{
		// Players who stopped editing (done, cancel, quit...): take their lines away.
		views.entrySet().removeIf(v -> {
			if(sessions.containsKey(v.getKey()))
				return false;
			v.getValue().clear();
			return true;
		});
		var it = sessions.entrySet().iterator();
		while(it.hasNext())
		{
			var e = it.next();
			Player p = Bukkit.getPlayer(e.getKey());
			Session s = e.getValue();
			if(p == null)
			{
				it.remove();
				LineView v = views.remove(e.getKey());
				if(v != null)
					v.clear();
				continue;
			}
			LineView view = views.computeIfAbsent(e.getKey(), k -> new LineView(plugin));
			Barrier b = store.find(s.world, s.name);
			List<double[]> boxes = b == null ? null : store.getCustom(b);
			if(boxes == null || !p.getWorld().getName().equals(s.world) || !s.area().clone().expand(32).contains(p.getLocation().toVector()))
			{
				view.clear();
				continue;
			}
			Set<LineView.Line> lines = new LinkedHashSet<>();
			// What is looked at first (it matters most), then the area outline, then the cubes nearest first.
			Aim aim = aim(p, s, boxes);
			// Grid only where something can be placed or cut: inside the area, or on one of the boxes.
			if(aim.face() != null && (!aim.remove().isEmpty() || snap(s, aim.face().target(), 1.0 / s.div) != null))
				grid(lines, aim.face(), 1.0 / s.div);
			for(double[] r : aim.remove())
				LineView.box(lines, r, LineView.Style.REMOVE);
			for(double[] pl : aim.place())
				LineView.box(lines, pl, LineView.Style.PREVIEW);
			LineView.box(lines, new double[] {s.x0, s.y0, s.z0, s.x1 + 1, s.y1 + 1, s.z1 + 1}, LineView.Style.AREA);
			Vector eye = p.getEyeLocation().toVector();
			List<double[]> near = new ArrayList<>();
			for(double[] box : boxes)
				if(dist2(box, eye) <= CUBE_DRAW_RANGE * CUBE_DRAW_RANGE)
					near.add(box);
			near.sort(java.util.Comparator.comparingDouble(box -> dist2(box, eye)));
			for(double[] box : near)
			{
				if(lines.size() + 12 > MAX_LINES)
					break;
				LineView.box(lines, box, LineView.Style.CUBE);
			}
			view.show(p, lines);
		}
	}

	/** Most lines shown to one editing player at once (each is one small display entity only they can see). */
	private static final int MAX_LINES = 600;
	/** Cubes farther than this from the eyes aren't outlined. */
	private static final double CUBE_DRAW_RANGE = 10;
	/** The lines each editing player sees. Main thread only. */
	private final Map<UUID, LineView> views = new HashMap<>();

	/** Removes every editor line (plugin disable). */
	void clearLines()
	{
		for(LineView v : views.values())
			v.clear();
		views.clear();
	}

	/** Distance squared from a point to the nearest point of a box. */
	private static double dist2(double[] b, Vector v)
	{
		double dx = Math.max(Math.max(b[0] - v.getX(), 0), v.getX() - b[3]);
		double dy = Math.max(Math.max(b[1] - v.getY(), 0), v.getY() - b[4]);
		double dz = Math.max(Math.max(b[2] - v.getZ(), 0), v.getZ() - b[5]);
		return dx * dx + dy * dy + dz * dz;
	}

	/** The grid lines, on the surface looked at, of the one cell a new cube would go into (each cell has its own grid). */
	private static void grid(Set<LineView.Line> out, Face face, double g)
	{
		Vector n = face.normal();
		int axis = Math.abs(n.getX()) > 0.5 ? 0 : Math.abs(n.getY()) > 0.5 ? 1 : 2;
		Vector target = face.target();
		double[] base = {Math.floor(target.getX()), Math.floor(target.getY()), Math.floor(target.getZ())};
		double[] hit = {face.hit().getX(), face.hit().getY(), face.hit().getZ()};
		int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
		double fixed = hit[axis] + 0.01 * (n.getX() + n.getY() + n.getZ()); // just toward the player, so the lines aren't hidden
		for(double u = 0; u <= 1 + 1e-9; u += g)
		{
			// One line across the cell face along a2 at a1 = u, and one along a1 at a2 = u.
			double[] p0 = new double[3], p1 = new double[3];
			p0[axis] = p1[axis] = fixed;
			p0[a1] = p1[a1] = base[a1] + u;
			p0[a2] = base[a2];
			p1[a2] = base[a2] + 1;
			out.add(LineView.Line.of(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], LineView.Style.GRID));
			double[] q0 = new double[3], q1 = new double[3];
			q0[axis] = q1[axis] = fixed;
			q0[a2] = q1[a2] = base[a2] + u;
			q0[a1] = base[a1];
			q1[a1] = base[a1] + 1;
			out.add(LineView.Line.of(q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], LineView.Style.GRID));
		}
	}

	private static String fmt(double v)
	{
		return String.format(java.util.Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}
}
