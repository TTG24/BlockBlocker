package dev.blockblocker;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
					barriers.put(name, new Barrier(name, world, min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2), footprint));
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
		}
		yaml.save(file);
	}

	private void reindex()
	{
		Map<String, List<CachedBox>> index = new HashMap<>();
		for(Barrier b : barriers.values())
			index.computeIfAbsent(b.world(), k -> new ArrayList<>()).add(new CachedBox(b, b.box()));
		for(Map<String, Barrier> perWorld : temporary.values())
			for(Barrier b : perWorld.values())
				index.computeIfAbsent(b.world(), k -> new ArrayList<>()).add(new CachedBox(b, b.box()));
		byWorld = index;
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
				copies.put(b.name(), new Barrier(b.name(), toWorld, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.footprint()));
		if(copies.isEmpty())
			temporary.remove(toWorld);
		else
			temporary.put(toWorld, copies);
		reindex();
		return copies.size();
	}

	/** Drops the temporary barriers of a world (it was unloaded or deleted). */
	public void clearTemporary(String world)
	{
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
		reindex();
	}

	public boolean remove(String name)
	{
		boolean removed = barriers.remove(name) != null;
		if(removed)
			reindex();
		return removed;
	}

	public int clear()
	{
		int n = barriers.size();
		barriers.clear();
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

	private boolean respectPartial = true;

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
			if(!respectPartial && barrier.footprint() == null)
				return true; // plain cuboid: the bounding box is exact

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
						if(!respectPartial)
							return true;
						if(!world.isChunkLoaded(x >> 4, z >> 4) || !isPartialBlock(world.getBlockAt(x, y, z)))
							return true;
					}
		}
		return false;
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
			if(c.barrier().footprint() == null)
				return true;
			// Shaped barrier: the box is only a broad phase, so walk the segment and test real cells.
			for(double t = 0; t <= len; t += 0.2)
				if(c.barrier().contains((int) Math.floor(a.getX() + dir.getX() * t), (int) Math.floor(a.getY() + dir.getY() * t), (int) Math.floor(a.getZ() + dir.getZ() * t)))
					return true;
		}
		return false;
	}
}
