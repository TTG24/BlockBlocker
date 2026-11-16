package dev.blockblocker;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds every barrier, persists them to data.yml and answers collision queries. Main thread only. */
public class BarrierStore
{
	private static final List<CachedBox> NONE = List.of();

	private record CachedBox(Barrier barrier, BoundingBox box)
	{
	}

	private final File file;
	private final Map<String, Barrier> barriers = new LinkedHashMap<>();
	private Map<String, List<CachedBox>> byWorld = new HashMap<>();

	public BarrierStore(File file)
	{
		this.file = file;
	}

	public void load()
	{
		barriers.clear();
		edges.keySet().removeIf(k -> !isTemporaryKey(k));
		customs.keySet().removeIf(k -> !isTemporaryKey(k));
		noShapes.removeIf(k -> !isTemporaryKey(k));
		if(file.exists())
		{
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
			ConfigurationSection section = yaml.getConfigurationSection("barriers");
			if(section != null)
			{
				for(String name : section.getKeys(false))
				{
					ConfigurationSection s = section.getConfigurationSection(name);
					if(s == null)
						continue;
					List<Integer> min = s.getIntegerList("min");
					List<Integer> max = s.getIntegerList("max");
					String world = s.getString("world");
					if(world == null || min.size() != 3 || max.size() != 3)
						continue;
					Footprint footprint = null;
					List<String> lines = s.getStringList("footprint");
					if(!lines.isEmpty())
					{
						try
						{
							footprint = Footprint.decode(lines, min.get(0), max.get(0));
						}
						catch(RuntimeException e)
						{
							continue; // corrupt shape data: skip this barrier rather than guess
						}
					}
					Volume volume = null;
					List<String> volumeLines = s.getStringList("volume");
					if(!volumeLines.isEmpty())
					{
						try
						{
							volume = Volume.decode(volumeLines, min.get(0), max.get(0), min.get(2), max.get(2));
						}
						catch(RuntimeException e)
						{
							continue; // corrupt shape data: skip this barrier rather than guess
						}
					}
					barriers.put(name, new Barrier(name, world, min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2), footprint, volume));
					BlockFace edge = parseEdge(s.getString("edge"));
					if(edge != null)
						edges.put(key(world, name), edge);
					List<double[]> custom = parseCustom(s.getList("custom"));
					if(custom != null)
						customs.put(key(world, name), custom);
					if(!s.getBoolean("shapes", true))
						noShapes.add(key(world, name));
				}
			}
		}
		reindex();
	}

	public void save() throws IOException
	{
		YamlConfiguration yaml = new YamlConfiguration();
		for(Barrier b : barriers.values())
		{
			String path = "barriers." + b.name();
			yaml.set(path + ".world", b.world());
			yaml.set(path + ".min", List.of(b.minX(), b.minY(), b.minZ()));
			yaml.set(path + ".max", List.of(b.maxX(), b.maxY(), b.maxZ()));
			if(b.footprint() != null)
				yaml.set(path + ".footprint", b.footprint().encode());
			if(b.volume() != null)
				yaml.set(path + ".volume", b.volume().encode());
			BlockFace edge = getEdge(b);
			if(edge != null)
				yaml.set(path + ".edge", edge.name().toLowerCase(java.util.Locale.ROOT));
			List<double[]> custom = getCustom(b);
			if(custom != null)
			{
				List<String> lines = new ArrayList<>();
				for(double[] box : custom)
					lines.add(box[0] + " " + box[1] + " " + box[2] + " " + box[3] + " " + box[4] + " " + box[5]);
				yaml.set(path + ".custom", lines);
			}
			if(!hasShapes(b))
				yaml.set(path + ".shapes", false);
		}
		yaml.save(file);
	}

	private void reindex()
	{
		Map<String, List<CachedBox>> index = new HashMap<>();
		for(Barrier b : barriers.values())
			if(!isDisabled(b))
				index.computeIfAbsent(b.world(), k -> new ArrayList<>()).add(new CachedBox(b, b.box()));
		for(Map<String, Barrier> perWorld : temporary.values())
			for(Barrier b : perWorld.values())
				if(!isDisabled(b))
					index.computeIfAbsent(b.world(), k -> new ArrayList<>()).add(new CachedBox(b, b.box()));
		byWorld = index;
	}

	/** Barriers switched off at runtime ("world/name"). Never saved: they come back on restart or /block reload. */
	private final Set<String> disabled = new HashSet<>();

	private static String key(String world, String name)
	{
		return world + "/" + name;
	}

	public boolean isDisabled(Barrier b)
	{
		return disabled.contains(key(b.world(), b.name()));
	}

	/** A barrier by name in one world: a saved one, or a temporary copy (arena instance). Includes disabled ones. */
	public Barrier find(String world, String name)
	{
		Barrier b = barriers.get(name);
		if(b != null && b.world().equals(world))
			return b;
		Map<String, Barrier> perWorld = temporary.get(world);
		return perWorld == null ? null : perWorld.get(name);
	}

	/** Every barrier in a world, including disabled ones (so they can still be found and switched back on). */
	public List<Barrier> allBarriersIn(String world)
	{
		List<Barrier> out = new ArrayList<>();
		for(Barrier b : barriers.values())
			if(b.world().equals(world))
				out.add(b);
		Map<String, Barrier> perWorld = temporary.get(world);
		if(perWorld != null)
			out.addAll(perWorld.values());
		return out;
	}

	/** @return false if no such barrier exists in that world. */
	public boolean setDisabled(String world, String name, boolean off)
	{
		if(find(world, name) == null)
			return false;
		if(off)
			disabled.add(key(world, name));
		else
			disabled.remove(key(world, name));
		reindex();
		return true;
	}

	public void enableAll()
	{
		if(!disabled.isEmpty())
		{
			disabled.clear();
			reindex();
		}
	}

	/** In-memory only barriers (never saved): copies made for temporary worlds such as arena instances. */
	private final Map<String, Map<String, Barrier>> temporary = new HashMap<>();

	/**
	 * Copies every saved barrier of one world into another as temporary barriers (same coordinates). Safe to call again
	 * for the same target: it replaces the earlier copy.
	 *
	 * @return how many barriers were copied
	 */
	public int copyWorld(String fromWorld, String toWorld)
	{
		Map<String, Barrier> copies = new LinkedHashMap<>();
		for(Barrier b : barriers.values())
			if(b.world().equals(fromWorld))
			{
				copies.put(b.name(), new Barrier(b.name(), toWorld, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.footprint(), b.volume()));
				BlockFace edge = getEdge(b);
				if(edge != null)
					edges.put(key(toWorld, b.name()), edge);
				List<double[]> custom = getCustom(b);
				if(custom != null)
					customs.put(key(toWorld, b.name()), custom);
				if(!hasShapes(b))
					noShapes.add(key(toWorld, b.name()));
				else
					noShapes.remove(key(toWorld, b.name()));
			}
		if(copies.isEmpty())
			temporary.remove(toWorld);
		else
			temporary.put(toWorld, copies);
		disabled.removeIf(k -> k.startsWith(toWorld + "/")); // a fresh copy starts enabled
		reindex();
		return copies.size();
	}

	/** The worlds that have temporary barrier copies (arena instances). */
	public java.util.Set<String> temporaryWorlds()
	{
		return new java.util.HashSet<>(temporary.keySet());
	}

	/** The temporary barrier copies of one world, including switched-off ones. */
	public List<Barrier> temporaryIn(String world)
	{
		Map<String, Barrier> perWorld = temporary.get(world);
		return perWorld == null ? List.of() : new ArrayList<>(perWorld.values());
	}

	/** Drops the temporary barriers of a world (it was unloaded or deleted). */
	public void clearTemporary(String world)
	{
		edges.keySet().removeIf(k -> k.startsWith(world + "/"));
		customs.keySet().removeIf(k -> k.startsWith(world + "/"));
		noShapes.removeIf(k -> k.startsWith(world + "/"));
		disabled.removeIf(k -> k.startsWith(world + "/"));
		if(temporary.remove(world) != null)
			reindex();
	}

	public boolean has(String name)
	{
		return barriers.containsKey(name);
	}

	public Barrier get(String name)
	{
		return barriers.get(name);
	}

	public List<Barrier> all()
	{
		return new ArrayList<>(barriers.values());
	}

	public boolean isEmpty()
	{
		return barriers.isEmpty();
	}

	public void add(Barrier barrier)
	{
		barriers.put(barrier.name(), barrier);
		disabled.remove(key(barrier.world(), barrier.name())); // a (re)created barrier starts enabled
		reindex();
	}

	public boolean remove(String name)
	{
		Barrier b = barriers.remove(name);
		if(b != null)
		{
			disabled.remove(key(b.world(), b.name()));
			edges.remove(key(b.world(), b.name()));
			customs.remove(key(b.world(), b.name()));
			noShapes.remove(key(b.world(), b.name()));
			reindex();
		}
		return b != null;
	}

	public int clear()
	{
		int n = barriers.size();
		for(Barrier b : barriers.values())
		{
			edges.remove(key(b.world(), b.name()));
			customs.remove(key(b.world(), b.name()));
			noShapes.remove(key(b.world(), b.name()));
		}
		barriers.clear();
		disabled.clear();
		reindex();
		return n;
	}

	/** Lowest unused numeric name: "1", "2", ... */
	public String nextAutoName()
	{
		int i = 1;
		while(barriers.containsKey(String.valueOf(i)))
			i++;
		return String.valueOf(i);
	}

	public List<Barrier> barriersIn(String world)
	{
		List<Barrier> out = new ArrayList<>();
		for(CachedBox c : byWorld.getOrDefault(world, NONE))
			out.add(c.barrier());
		return out;
	}

	public boolean hasBarriersIn(String world)
	{
		return !byWorld.getOrDefault(world, NONE).isEmpty();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Edge barriers: instead of filling its cells, the barrier is a thin plate on one side of each cell.
	// ---------------------------------------------------------------------------------------------------------------

	/** Thickness of an edge plate, in sixteenths of a block (client tiny shulkers and the server use the same plate). */
	public static final int EDGE_THICKNESS_16 = 4;

	/** Edge side of each edge barrier, by "world/name" (saved ones and temporary copies). */
	private final Map<String, BlockFace> edges = new HashMap<>();

	private boolean isTemporaryKey(String k)
	{
		String world = k.substring(0, k.indexOf('/'));
		return temporary.containsKey(world);
	}

	static BlockFace parseEdge(String s)
	{
		if(s == null)
			return null;
		return switch(s.toLowerCase(java.util.Locale.ROOT))
		{
			case "north" -> BlockFace.NORTH;
			case "south" -> BlockFace.SOUTH;
			case "east" -> BlockFace.EAST;
			case "west" -> BlockFace.WEST;
			default -> null;
		};
	}

	/** Barriers ("world/name") that do NOT copy bar/fence/pane/wall shapes into the air above/below: plain full cells. */
	private final Set<String> noShapes = new HashSet<>();

	/** Does this barrier copy the shape of thin blocks (bars, fences...) into its air cells? (Made with /block addshape.) */
	public boolean hasShapes(Barrier b)
	{
		return !noShapes.contains(key(b.world(), b.name()));
	}

	public void setShapes(Barrier b, boolean on)
	{
		if(on)
			noShapes.remove(key(b.world(), b.name()));
		else
			noShapes.add(key(b.world(), b.name()));
	}

	/** {@link #shapeAbove(Block)}, but null when this barrier doesn't copy shapes, and only copying what belongs to it (see sourceFor). */
	public List<int[]> shapeAbove(Barrier barrier, Block air)
	{
		if(!hasShapes(barrier))
			return null;
		Block b = sourceFor(barrier, air);
		return b == null ? null : stackFootprints(b, b.getY() < air.getY() ? -1 : 1);
	}

	/**
	 * The block an air cell of this barrier copies its shape from: the first block straight below it (else above), looking
	 * only through the barrier's own cells. A thin/partial block inside the barrier counts; one just outside it (the block
	 * the barrier stands on, or hangs under) counts only if it is a thin one (bars, fences, panes, walls): stairs or a slab
	 * the barrier merely sits over are floor, not part of the wall. Null if none.
	 */
	public Block sourceFor(Barrier barrier, Block air)
	{
		if((thinBlocks.isEmpty() && !copyPartial) || !isOpen(air))
			return null;
		Block below = sourceAlong(barrier, air, -1);
		return below != null ? below : sourceAlong(barrier, air, 1);
	}

	private Block sourceAlong(Barrier barrier, Block air, int dir)
	{
		World w = air.getWorld();
		for(int y = air.getY() + dir, n = 0; y >= w.getMinHeight() && y < w.getMaxHeight() && n < 32; y += dir, n++)
		{
			Block b = w.getBlockAt(air.getX(), y, air.getZ());
			boolean inside = barrier.contains(b.getX(), b.getY(), b.getZ());
			if(isOpen(b))
			{
				if(!inside)
					return null; // left the barrier through air: nothing of it to copy
				continue;
			}
			if(inside)
				return copiesShape(b) ? b : null;
			return isThin(b) ? b : null;
		}
		return null;
	}

	/**
	 * An empty cell of a shape barrier with nothing to copy, in a line of thin cells: going this way through the barrier
	 * (past other such empty cells, up to 8), does it reach a thin block (or an air cell copying one) that joins up? Then the
	 * cell is a thin piece continuing that line (a gap in a row of bars). Returns that block, else null.
	 */
	private Block lineEnd(Barrier barrier, Block air, BlockFace f)
	{
		Block c = air;
		for(int k = 0; k < 8; k++)
		{
			c = c.getRelative(f);
			if(!barrier.contains(c.getX(), c.getY(), c.getZ()))
				return null;
			if(isOpen(c))
			{
				Block src = sourceFor(barrier, c);
				if(src != null)
					return connects(src) ? src : null;
				continue; // another gap: keep going
			}
			return isThin(c) && connects(c) ? c : null;
		}
		return null;
	}

	/** The sides an empty, nothing-to-copy cell continues a thin line toward (see lineEnd), with the thickest arm found. */
	private List<BlockFace> lineSides(Barrier barrier, Block air, int[] width)
	{
		List<BlockFace> out = new ArrayList<>(2);
		if(!connectShapes || !hasShapes(barrier) || !isOpen(air) || sourceFor(barrier, air) != null)
			return out;
		int w = 0;
		for(BlockFace f : SIDES)
		{
			Block end = lineEnd(barrier, air, f);
			if(end == null)
				continue;
			out.add(f);
			List<int[]> fp = footprints(end);
			if(fp != null)
				w = Math.max(w, armWidth16(end, fp));
		}
		if(width != null)
			width[0] = w == 0 ? 2 : w;
		return out;
	}

	/** Does an air cell of the barrier continue a thin line on this side (a gap piece in a row of bars)? */
	private boolean continuesLine(Barrier barrier, Block air, BlockFace toward)
	{
		return isOpen(air) && sourceFor(barrier, air) == null && hasShapes(barrier) && lineEnd(barrier, air, toward) != null;
	}

	/** The side this barrier is a thin plate on, or null for a normal (whole-cell) barrier. */
	public BlockFace getEdge(Barrier b)
	{
		return edges.get(key(b.world(), b.name()));
	}

	/** Makes a barrier an edge barrier (a thin plate on that side of each cell), or a normal one again with null. */
	public void setEdge(Barrier b, BlockFace face)
	{
		if(face == null)
			edges.remove(key(b.world(), b.name()));
		else
			edges.put(key(b.world(), b.name()), face);
	}

	/** The edge plate of a cell as a footprint {x0, z0, x1, z1} in sixteenths, over the whole cell height. */
	static int[] edgeFootprint(BlockFace face)
	{
		int t = EDGE_THICKNESS_16;
		return switch(face)
		{
			case NORTH -> new int[] {0, 0, 16, t};
			case SOUTH -> new int[] {0, 16 - t, 16, 16};
			case WEST -> new int[] {0, 0, t, 16};
			default -> new int[] {16 - t, 0, 16, 16}; // EAST
		};
	}

	/** Custom barriers: exact boxes (absolute block coordinates, decimals allowed), by "world/name". */
	private final Map<String, List<double[]>> customs = new HashMap<>();

	/** The exact boxes {minX, minY, minZ, maxX, maxY, maxZ} of a custom barrier (unmodifiable), or null for any other barrier. */
	public List<double[]> getCustom(Barrier b)
	{
		return customs.get(key(b.world(), b.name()));
	}

	/** Makes a barrier a custom one made of these boxes (an empty list is allowed), or a normal one again with null. */
	public void setCustom(Barrier b, List<double[]> boxes)
	{
		if(boxes == null)
			customs.remove(key(b.world(), b.name()));
		else
		{
			List<double[]> copy = new ArrayList<>(boxes.size());
			for(double[] box : boxes)
				copy.add(box.clone());
			customs.put(key(b.world(), b.name()), java.util.Collections.unmodifiableList(copy));
		}
	}

	/** Reads "custom" from data.yml: a list of "minX minY minZ maxX maxY maxZ" lines (or one plain list of 6 numbers). */
	private static List<double[]> parseCustom(List<?> raw)
	{
		if(raw == null || raw.isEmpty())
			return null;
		List<double[]> out = new ArrayList<>();
		try
		{
			if(raw.get(0) instanceof Number && raw.size() == 6)
			{
				double[] box = new double[6];
				for(int i = 0; i < 6; i++)
					box[i] = ((Number) raw.get(i)).doubleValue();
				out.add(box);
			}
			else
				for(Object line : raw)
				{
					String[] parts = String.valueOf(line).trim().split("\\s+");
					if(parts.length != 6)
						continue;
					double[] box = new double[6];
					for(int i = 0; i < 6; i++)
						box[i] = Double.parseDouble(parts[i]);
					out.add(box);
				}
		}
		catch(RuntimeException e)
		{
			return null; // corrupt: treat as a plain barrier rather than guess
		}
		return java.util.Collections.unmodifiableList(out);
	}

	static BoundingBox edgePlate(BlockFace face, int x, int y, int z)
	{
		int[] f = edgeFootprint(face);
		return new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0);
	}

	/**
	 * The solid parts of one cell of a barrier, exactly as the server enforces them (absolute coordinates). Empty = nothing
	 * solid there (the block's own collision applies, or a push-mode shape). Used by the server-side check.
	 */
	public List<BoundingBox> cellBoxes(World world, Barrier barrier, int x, int y, int z)
	{
		List<double[]> custom = getCustom(barrier);
		if(custom != null)
		{
			BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
			List<BoundingBox> out = new ArrayList<>();
			for(double[] c : custom)
			{
				BoundingBox box = new BoundingBox(c[0], c[1], c[2], c[3], c[4], c[5]);
				if(box.overlaps(cell))
					out.add(box.intersection(cell));
			}
			return out;
		}
		BlockFace edge = getEdge(barrier);
		if(edge != null)
			return List.of(edgePlate(edge, x, y, z));
		BoundingBox full = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
		if(!world.isChunkLoaded(x >> 4, z >> 4))
			return List.of(full);
		Block block = world.getBlockAt(x, y, z);
		if(hasShapes(barrier) && copiesShape(block))
		{
			// Shape barrier (/block addshape): iron bars etc. keep their own collision, not the whole cell, plus an arm
			// bridging to a thin neighbour they don't join by themselves (a fence beside bars).
			if(!enforceShapes)
				return List.of();
			if(isThin(block))
			{
				List<BoundingBox> arms = new ArrayList<>();
				for(int[] f : thinCellExtras(barrier, block))
					arms.add(new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0));
				return arms;
			}
			// Doors, trapdoors, slabs...: their footprint over the whole cell height (so nobody stands on a slab inside it).
			List<int[]> fp = footprints(block);
			if(fp == null)
				return List.of();
			List<BoundingBox> out = new ArrayList<>();
			for(int[] f : fp)
				out.add(new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0));
			return out;
		}
		if((!thinBlocks.isEmpty() || copyPartial) && !blockFill && isOpen(block))
		{
			// Air above (or below) a thin block copies that block's shape (joined up with its thin neighbours).
			List<int[]> shape = shapeIn(barrier, block);
			if(shape != null)
			{
				if(!enforceShapes)
					return List.of(); // push mode: not solid
				List<BoundingBox> out = new ArrayList<>();
				for(int[] f : shape)
					out.add(new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0));
				return out;
			}
		}
		if(!respectPartial || !isPartialBlock(block))
			return List.of(full);
		return List.of();
	}

	private boolean connectShapes = true;

	/** Copied shapes join up with neighbouring thin cells of the same barrier, like bars connecting to each other. */
	public void setConnectShapes(boolean connectShapes)
	{
		this.connectShapes = connectShapes;
	}

	private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

	/**
	 * The sides of an air cell (whose shape is a copy) where the next cell of the same barrier is also thin: a thin block
	 * (bar, fence...) or another copied cell. With connect-shapes on, the copy reaches across to those sides.
	 */
	public List<BlockFace> connections(Barrier barrier, Block air)
	{
		List<BlockFace> out = new ArrayList<>(4);
		if(!connectShapes)
			return out;
		for(BlockFace f : SIDES)
		{
			Block n = air.getRelative(f);
			if(!barrier.contains(n.getX(), n.getY(), n.getZ()))
				continue;
			// Only toward bars/fences/panes/walls (or air copying one, or a gap piece of the line): doors, slabs... don't connect.
			Block src = isOpen(n) ? sourceFor(barrier, n) : n;
			if(src != null ? connects(src) && (isThin(n) || shapeAbove(barrier, n) != null) : continuesLine(barrier, n, f.getOppositeFace()))
				out.add(f);
		}
		return out;
	}

	/**
	 * The copied shape of an air cell inside a barrier: the shape of the thin block above/below it, plus (connect-shapes) an
	 * arm, as thick as that block's post, toward every side where the barrier continues with thin cells. So one selection
	 * over a row of bars becomes one continuous thin wall instead of separate posts. Footprints {x0, z0, x1, z1} in 16ths.
	 */
	public List<int[]> shapeIn(Barrier barrier, Block air)
	{
		List<int[]> base = shapeAbove(barrier, air);
		if(base == null)
		{
			// Nothing to copy, but in a line of bars/fences: a thin piece joining it up (as thick as their arms)
			int[] width = new int[1];
			List<BlockFace> line = lineSides(barrier, air, width);
			if(line.isEmpty())
				return null;
			int lo = 8 - width[0] / 2, hi = lo + width[0];
			List<int[]> out = new ArrayList<>();
			out.add(new int[] {lo, lo, hi, hi});
			for(BlockFace f : line)
				out.add(switch(f)
				{
					case NORTH -> new int[] {lo, 0, hi, hi};
					case SOUTH -> new int[] {lo, lo, hi, 16};
					case WEST -> new int[] {0, lo, hi, hi};
					default -> new int[] {lo, lo, 16, hi}; // EAST
				});
			return out;
		}
		if(!connectShapes || !connects(sourceFor(barrier, air)))
			return base;
		List<BlockFace> sides = connections(barrier, air);
		if(sides.isEmpty())
			return base;
		// The arms are as thick as the block's own arms (a wall's arms are thinner than its post): taken from an arm the block
		// already has (a footprint reaching a side of the cell), else the usual arm of that kind of block, else the post.
		int w = armWidth16(sourceFor(barrier, air), base);
		int lo = 8 - w / 2, hi = lo + w;
		// (the arm sits where the block's own arms do: centred like them)
		for(int[] f : base)
			if((f[0] == 0 || f[2] == 16) && f[3] - f[1] == w)
			{
				lo = f[1];
				hi = f[3];
				break;
			}
			else if((f[1] == 0 || f[3] == 16) && f[2] - f[0] == w)
			{
				lo = f[0];
				hi = f[2];
				break;
			}
		List<int[]> out = new ArrayList<>(base);
		for(BlockFace f : sides)
		{
			int[] arm = switch(f)
			{
				case NORTH -> new int[] {lo, 0, hi, hi};
				case SOUTH -> new int[] {lo, lo, hi, 16};
				case WEST -> new int[] {0, lo, hi, hi};
				default -> new int[] {lo, lo, 16, hi}; // EAST
			};
			boolean dup = false;
			for(int[] o : out)
				dup |= java.util.Arrays.equals(o, arm);
			if(!dup)
				out.add(arm);
		}
		return out;
	}

	private boolean respectPartial = true;
	private boolean enforceShapes;
	private boolean fillCell;
	private boolean blockFill;

	/** Block mode: air cells above thin blocks are a plain full cell (no gaps) instead of the thin shape. */
	public boolean isBlockFill()
	{
		return blockFill;
	}

	public void setBlockFill(boolean blockFill)
	{
		this.blockFill = blockFill;
	}

	/** Push mode: push out of the whole cell, not only the thin shape. */
	public void setFillCell(boolean fillCell)
	{
		this.fillCell = fillCell;
	}

	/** Should the server also enforce the copied thin shapes? Off by default: it can disagree with the client (rubber-banding). */
	public void setEnforceShapes(boolean enforceShapes)
	{
		this.enforceShapes = enforceShapes;
	}

	public void setRespectPartial(boolean respectPartial)
	{
		this.respectPartial = respectPartial;
	}

	/**
	 * True if the box overlaps (strictly, so touching a face is fine) any barrier in the world. With respectPartial on,
	 * cells holding a partial block (fence, slab, stairs, chest...) are skipped: that block's own collision applies there,
	 * which the player's client already knows about, so there is no rubber-banding.
	 */
	public boolean intersects(World world, BoundingBox box)
	{
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
		{
			if(!c.box().overlaps(box))
				continue;
			Barrier barrier = c.barrier();
			// The overlap with a player-sized box is only a few cells, so looking at the blocks is cheap. It has to be done
			// even for a plain cuboid, because cells holding thin blocks (iron bars...) are not part of the wall.

			BoundingBox ov = c.box().clone().intersection(box);
			int x0 = (int) Math.floor(ov.getMinX()), x1 = (int) Math.ceil(ov.getMaxX()) - 1;
			int y0 = (int) Math.floor(ov.getMinY()), y1 = (int) Math.ceil(ov.getMaxY()) - 1;
			int z0 = (int) Math.floor(ov.getMinZ()), z1 = (int) Math.ceil(ov.getMaxZ()) - 1;
			for(int x = x0; x <= x1; x++)
				for(int y = y0; y <= y1; y++)
					for(int z = z0; z <= z1; z++)
					{
						if(!barrier.contains(x, y, z))
							continue; // inside the bounding box but outside the actual shape
						for(BoundingBox cell : cellBoxes(world, barrier, x, y, z))
							if(cell.overlaps(box))
								return true;
					}
		}
		return false;
	}

	/**
	 * Like {@link #intersects}, but only counting barrier cells whose real block has no collision (air, grass...). Used for
	 * "is a mob standing on nothing but a barrier": a barrier cell holding a real block (iron bars in a floor) is ground.
	 */
	/** Name of a barrier overlapping the box (for debug messages), or null. */
	public String nameAt(World world, BoundingBox box)
	{
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
		{
			if(!c.box().overlaps(box))
				continue;
			Barrier barrier = c.barrier();
			BoundingBox ov = c.box().clone().intersection(box);
			for(int x = (int) Math.floor(ov.getMinX()); x <= (int) Math.ceil(ov.getMaxX()) - 1; x++)
				for(int y = (int) Math.floor(ov.getMinY()); y <= (int) Math.ceil(ov.getMaxY()) - 1; y++)
					for(int z = (int) Math.floor(ov.getMinZ()); z <= (int) Math.ceil(ov.getMaxZ()) - 1; z++)
					{
						if(!barrier.contains(x, y, z))
							continue;
						for(BoundingBox cell : cellBoxes(world, barrier, x, y, z))
							if(cell.overlaps(box))
								return barrier.name();
					}
		}
		return null;
	}

	/** Is the block right under this cell a real one (not passable), so a barrier in this cell lies on it? */
	public boolean restsOnBlock(World world, int x, int y, int z)
	{
		return !world.getBlockAt(x, y - 1, z).isPassable();
	}

	public boolean intersectsEmpty(World world, BoundingBox box)
	{
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
		{
			if(!c.box().overlaps(box))
				continue;
			Barrier barrier = c.barrier();
			BoundingBox ov = c.box().clone().intersection(box);
			int x0 = (int) Math.floor(ov.getMinX()), x1 = (int) Math.ceil(ov.getMaxX()) - 1;
			int y0 = (int) Math.floor(ov.getMinY()), y1 = (int) Math.ceil(ov.getMaxY()) - 1;
			int z0 = (int) Math.floor(ov.getMinZ()), z1 = (int) Math.ceil(ov.getMaxZ()) - 1;
			for(int x = x0; x <= x1; x++)
				for(int y = y0; y <= y1; y++)
					for(int z = z0; z <= z1; z++)
					{
						if(!barrier.contains(x, y, z) || !world.isChunkLoaded(x >> 4, z >> 4) || !world.getBlockAt(x, y, z).isPassable())
							continue;
						// One barrier layer lying straight on a real block (blockers over a floor of bars, a low step) can be
						// walked on like players do; only stacked or floating barrier cells (walls, tops above bars) are not ground.
						if(restsOnBlock(world, x, y, z))
							continue;
						for(BoundingBox cell : cellBoxes(world, barrier, x, y, z))
							if(cell.overlaps(box))
								return true;
					}
		}
		return false;
	}

	/**
	 * If the box overlaps the copied thin shape in an air cell above a thin block: the horizontal unit direction {dx, dz}
	 * that leads out of it (along the axis it is least deep in, away from the shape's centre). Otherwise null.
	 */
	public double[] pushFor(World world, BoundingBox box)
	{
		if(thinBlocks.isEmpty())
			return null;
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
		{
			if(!c.box().overlaps(box))
				continue;
			BoundingBox ov = c.box().clone().intersection(box);
			for(int x = (int) Math.floor(ov.getMinX()); x <= (int) Math.ceil(ov.getMaxX()) - 1; x++)
				for(int y = (int) Math.floor(ov.getMinY()); y <= (int) Math.ceil(ov.getMaxY()) - 1; y++)
					for(int z = (int) Math.floor(ov.getMinZ()); z <= (int) Math.ceil(ov.getMaxZ()) - 1; z++)
					{
						if(!c.barrier().contains(x, y, z) || !world.isChunkLoaded(x >> 4, z >> 4))
							continue;
						List<int[]> shape = shapeIn(c.barrier(), world.getBlockAt(x, y, z));
						if(shape == null)
							continue;
						for(int[] f : shape)
						{
							// With fillCell, the whole cell counts as the shape, so there is no gap between the pieces to stand in.
							BoundingBox sb = fillCell ? new BoundingBox(x, y, z, x + 1, y + 1, z + 1)
									: new BoundingBox(x + f[0] / 16.0, y, z + f[1] / 16.0, x + f[2] / 16.0, y + 1, z + f[3] / 16.0);
							if(!sb.overlaps(box))
								continue;
							double dx = box.getCenterX() - sb.getCenterX(), dz = box.getCenterZ() - sb.getCenterZ();
							if(Math.abs(dx) >= Math.abs(dz))
								return new double[]{dx >= 0 ? 1 : -1, 0};
							return new double[]{0, dz >= 0 ? 1 : -1};
						}
					}
		}
		return null;
	}

	/** Material-name endings that count as thin, sealing blocks (config "thin-blocks"). Upper case. */
	private List<String> thinBlocks = List.of();

	public void setThinBlocks(List<String> tokens)
	{
		List<String> clean = new ArrayList<>();
		for(String t : tokens)
			if(t != null && !t.isBlank())
				clean.add(t.trim().toUpperCase(java.util.Locale.ROOT));
		thinBlocks = clean;
	}

	/**
	 * Is this block one of the thin blocks (iron bars, copper bars, glass panes by default)? Their own collision is a thin
	 * wall that already stops players, so a barrier around them must not turn the whole cell solid.
	 */
	public boolean isThin(Block b)
	{
		return matchesThin(b.getType().name(), thinBlocks);
	}

	private boolean copyPartial = true;

	/** shape-copy-partial-blocks: doors, trapdoors, slabs, stairs... are copied too, not only the thin-blocks list. */
	public void setCopyPartial(boolean copyPartial)
	{
		this.copyPartial = copyPartial;
	}

	/**
	 * Does a shape barrier (/block addshape) copy this block's shape? The thin-blocks list (bars, panes, fences, walls), plus
	 * (shape-copy-partial-blocks) every other block with collision that isn't a full cube: doors, trapdoors, slabs, stairs...
	 */
	public boolean copiesShape(Block b)
	{
		return isThin(b) || (copyPartial && isPartialBlock(b));
	}

	/**
	 * The arms a thin block's OWN cell is missing: where the barrier continues sideways with another thin cell (a bar, a
	 * fence, or air copying one) that this block doesn't connect to by itself - e.g. a fence beside iron bars, which never
	 * join in vanilla and leave a gap a player can squeeze through. Footprints {x0, z0, x1, z1} (16ths), whole cell height,
	 * as thick as the block's own arms. Empty when nothing is missing.
	 */
	public List<int[]> bridgeArms(Barrier barrier, Block thin)
	{
		if(!connectShapes || !hasShapes(barrier) || !isThin(thin) || !connects(thin))
			return List.of();
		List<int[]> own = footprints(thin);
		if(own == null)
			return List.of();
		int w = armWidth16(thin, own);
		int lo = 8 - w / 2, hi = lo + w;
		List<int[]> out = new ArrayList<>(2);
		for(BlockFace f : SIDES)
		{
			Block n = thin.getRelative(f);
			if(!barrier.contains(n.getX(), n.getY(), n.getZ()))
				continue;
			Block src = isOpen(n) ? sourceFor(barrier, n) : n;
			if(src == null ? !continuesLine(barrier, n, f.getOppositeFace()) : !connects(src) || !(isThin(n) || shapeAbove(barrier, n) != null))
				continue;
			boolean reaches = false;
			for(int[] o : own)
				reaches |= switch(f)
				{
					case NORTH -> o[1] == 0;
					case SOUTH -> o[3] == 16;
					case WEST -> o[0] == 0;
					default -> o[2] == 16; // EAST
				};
			if(reaches)
				continue; // it already connects that way
			out.add(switch(f)
			{
				case NORTH -> new int[] {lo, 0, hi, hi};
				case SOUTH -> new int[] {lo, lo, hi, 16};
				case WEST -> new int[] {0, lo, hi, hi};
				default -> new int[] {lo, lo, 16, hi}; // EAST
			});
		}
		return out;
	}

	/** How thick (sixteenths) a connecting arm of this block is: one of its own arms if it has any, else its kind's usual arm. */
	private static int armWidth16(Block src, List<int[]> base)
	{
		// (the thickest arm: an outline joined from a stack, like bars on a wall, takes the wall's arms)
		int arm = 0;
		for(int[] f : base)
		{
			boolean alongX = f[0] == 0 || f[2] == 16, alongZ = f[1] == 0 || f[3] == 16;
			if(alongX && !alongZ)
				arm = Math.max(arm, f[3] - f[1]);
			else if(alongZ && !alongX)
				arm = Math.max(arm, f[2] - f[0]);
		}
		if(arm > 0)
			return arm;
		if(src != null)
		{
			org.bukkit.block.data.BlockData d = src.getBlockData();
			String n = src.getType().name();
			if(d instanceof org.bukkit.block.data.type.Wall)
				return 6; // walls: an 8 wide post, 6 wide arms
			if(n.endsWith("BARS") || n.endsWith("PANE"))
				return 2;
		}
		// Else (fences...) as thick as the post: the footprint covering the middle of the cell, else the thinnest one
		int w = 16;
		for(int[] f : base)
			if(f[0] <= 8 && f[2] >= 8 && f[1] <= 8 && f[3] >= 8)
				w = Math.min(w, Math.min(f[2] - f[0], f[3] - f[1]));
		if(w == 16)
			for(int[] f : base)
				w = Math.min(w, Math.min(f[2] - f[0], f[3] - f[1]));
		return w;
	}

	/** Only these join up with their neighbours (arms between cells): blocks that connect on their own, like bars and fences. */
	private static boolean connects(Block b)
	{
		org.bukkit.block.data.BlockData d = b.getBlockData();
		return d instanceof org.bukkit.block.data.MultipleFacing || d instanceof org.bukkit.block.data.type.Wall;
	}

	/**
	 * The footprints {x0, z0, x1, z1} (sixteenths) of a block's own collision shape, seen from above. Stretched over the whole
	 * cell height, they are the shape a shape barrier keeps in that block's cell and copies into the air above/below it.
	 */
	public List<int[]> footprints(Block b)
	{
		List<int[]> out = new ArrayList<>();
		for(BoundingBox bb : b.getCollisionShape().getBoundingBoxes())
		{
			int[] f = {clamp16(bb.getMinX()), clamp16(bb.getMinZ()), clamp16(bb.getMaxX()), clamp16(bb.getMaxZ())};
			if(f[2] > f[0] && f[3] > f[1])
			{
				boolean dup = false;
				for(int[] o : out)
					dup |= java.util.Arrays.equals(o, f);
				if(!dup)
					out.add(f);
			}
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * For an air cell: if the first non-air block below it (within 32 cells) is a thin block, the footprints of that block's
	 * collision shape as {x0, z0, x1, z1} in sixteenths of a block (the full cell height is used). Otherwise null.
	 */
	public List<int[]> shapeAbove(Block air)
	{
		Block b = thinBelow(air);
		return b == null ? null : stackFootprints(b, b.getY() < air.getY() ? -1 : 1);
	}

	/**
	 * The outline of a thin/partial block together with every thin/partial block stacked straight on from it (further down
	 * for dir -1, up for +1), up to 8: their footprints joined. So bars standing on a wall keep the wall's thicker outline
	 * all the way up, and nobody can stand on the wall's top beside the bars ("the thicker block wins").
	 */
	public List<int[]> stackFootprints(Block start, int dir)
	{
		return stackFootprints(start, dir, false);
	}

	/**
	 * thinOnly: every block of the stack must be a thin one (walls, fences, panes, bars). Otherwise only the start block may
	 * be any partial block (a door copied upward); the rest of the stack beyond it is still thin blocks only. Floor-like
	 * blocks (stairs, slabs...) under bars are never part of the stack: their outline covers the whole cell.
	 */
	public List<int[]> stackFootprints(Block start, int dir, boolean thinOnly)
	{
		List<int[]> out = new ArrayList<>();
		Block cur = start;
		for(int i = 0; i < 8 && cur != null && cur.getY() >= cur.getWorld().getMinHeight() && cur.getY() < cur.getWorld().getMaxHeight()
				&& (i == 0 && !thinOnly ? copiesShape(cur) : isThin(cur)); i++)
		{
			List<int[]> fp = footprints(cur);
			if(fp != null)
				for(int[] f : fp)
				{
					boolean dup = false;
					for(int[] o : out)
						dup |= java.util.Arrays.equals(o, f);
					if(!dup)
						out.add(f);
				}
			cur = cur.getRelative(0, dir, 0);
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * What a thin block's own cell needs on top of its own collision, in a shape barrier: the outline of thicker thin/partial
	 * blocks stacked under it (bars on a wall get the wall's outline), and arms bridging to thin neighbours it doesn't join
	 * (a fence beside bars). Footprints, whole cell height.
	 */
	public List<int[]> thinCellExtras(Barrier barrier, Block thin)
	{
		if(!hasShapes(barrier) || !isThin(thin))
			return List.of();
		List<int[]> out = new ArrayList<>(bridgeArms(barrier, thin));
		List<int[]> under = stackFootprints(thin.getRelative(0, -1, 0), -1, true);
		if(under != null)
		{
			List<int[]> own = footprints(thin);
			for(int[] f : under)
			{
				boolean have = false;
				if(own != null)
					for(int[] o : own)
						have |= java.util.Arrays.equals(o, f);
				if(!have)
					out.add(f);
			}
		}
		return out;
	}

	/**
	 * For an air cell: the thin block it copies. The first non-air block below it (within 32 cells) if that is a thin block;
	 * otherwise the first non-air block above it (within 32 cells) if that is a thin block. Else null. (Only air cells inside
	 * a barrier are ever asked, so this only ever applies within the blocked selection.)
	 */
	public Block thinBelow(Block air)
	{
		if((thinBlocks.isEmpty() && !copyPartial) || !isOpen(air))
			return null;
		Block below = firstSolid(air, -1);
		if(below != null && copiesShape(below))
			return below;
		Block above = firstSolid(air, 1);
		return above != null && copiesShape(above) ? above : null;
	}

	private static Block firstSolid(Block from, int dir)
	{
		World w = from.getWorld();
		for(int y = from.getY() + dir, n = 0; y >= w.getMinHeight() && y < w.getMaxHeight() && n < 32; y += dir, n++)
		{
			Block b = w.getBlockAt(from.getX(), y, from.getZ());
			if(!isOpen(b))
				return b;
		}
		return null;
	}

	private static int clamp16(double v)
	{
		return Math.max(0, Math.min(16, (int) Math.round(v * 16)));
	}

	/** A material name matches a token if it equals it or ends with it ("BARS" matches IRON_BARS and COPPER_BARS). */
	static boolean matchesThin(String materialName, List<String> tokens)
	{
		for(String t : tokens)
			if(materialName.equals(t) || materialName.endsWith(t))
				return true;
		return false;
	}

	/**
	 * Empty as far as moving through goes: air, or a block with no collision at all (coral fans, plants, flowers, torches,
	 * signs, carpets of snow...). Shapes are copied up through these like through air (the block itself stays as it is).
	 */
	public static boolean isOpen(Block b)
	{
		if(b.getType().isAir())
			return true;
		return b.isPassable() && b.getCollisionShape().getBoundingBoxes().isEmpty();
	}

	/** A block with collision that isn't a full cube. */
	private static boolean isPartialBlock(Block b)
	{
		return !b.getType().isAir() && !b.isPassable() && !isFullCube(b);
	}

	/** A solid block whose collision is exactly one full cube (stone, dirt...). The client already collides with these itself. */
	static boolean isFullCube(Block b)
	{
		if(b.getType().isAir() || b.isPassable())
			return false;
		Collection<BoundingBox> boxes = b.getCollisionShape().getBoundingBoxes();
		if(boxes.size() != 1)
			return false;
		BoundingBox bb = boxes.iterator().next();
		return bb.getWidthX() >= 0.999 && bb.getHeight() >= 0.999 && bb.getWidthZ() >= 0.999;
	}

	/** Is any barrier's bounding box overlapping this box? A cheap first check before the exact ones. */
	public boolean near(World world, BoundingBox box)
	{
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
			if(c.box().overlaps(box))
				return true;
		return false;
	}

	/**
	 * True if the straight segment a -> b passes through the real shape of a barrier: each cell along it is checked against
	 * its exact boxes, so a thin piece at the edge of a cell blocks only the moves that really cross it.
	 */
	public boolean segmentHits(World world, org.bukkit.util.Vector a, org.bukkit.util.Vector b)
	{
		org.bukkit.util.Vector dir = b.clone().subtract(a);
		double len = dir.length();
		if(len < 1.0E-6)
			return false;
		dir.multiply(1.0 / len);
		for(CachedBox c : byWorld.getOrDefault(world.getName(), NONE))
		{
			if(!c.box().contains(a) && c.box().rayTrace(a, dir, len) == null)
				continue;
			Barrier barrier = c.barrier();
			java.util.Set<Long> seen = new java.util.HashSet<>();
			for(double t = 0; t <= len + 0.05; t += 0.1)
			{
				double d = Math.min(t, len);
				int x = (int) Math.floor(a.getX() + dir.getX() * d), y = (int) Math.floor(a.getY() + dir.getY() * d), z = (int) Math.floor(a.getZ() + dir.getZ() * d);
				if(!seen.add(((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF)) || !barrier.contains(x, y, z))
					continue;
				for(BoundingBox cell : cellBoxes(world, barrier, x, y, z))
					if(cell.contains(a) || cell.rayTrace(a, dir, len) != null)
						return true;
			}
		}
		return false;
	}

	/** True if the straight segment a -> b passes through any barrier in the world. */
	public boolean segmentCrosses(String world, org.bukkit.util.Vector a, org.bukkit.util.Vector b)
	{
		org.bukkit.util.Vector dir = b.clone().subtract(a);
		double len = dir.length();
		if(len < 1.0E-6)
			return false;
		dir.multiply(1.0 / len);
		for(CachedBox c : byWorld.getOrDefault(world, NONE))
		{
			if(!c.box().contains(a) && c.box().rayTrace(a, dir, len) == null)
				continue;
			if(!c.barrier().isShaped())
				return true;
			// Shaped barrier: the box is only a broad phase, so walk the segment and test real cells.
			for(double t = 0; t <= len; t += 0.2)
				if(c.barrier().contains((int) Math.floor(a.getX() + dir.getX() * t), (int) Math.floor(a.getY() + dir.getY() * t), (int) Math.floor(a.getZ() + dir.getZ() * t)))
					return true;
		}
		return false;
	}
}
