package dev.blockblocker;

import org.bukkit.util.BoundingBox;

/**
 * An invisible wall made of whole block cells. One of three forms:
 * <ul>
 * <li>a plain cuboid (min/max inclusive);</li>
 * <li>a footprint shape (polygon, cylinder): that cuboid's height range extruded over a footprint;</li>
 * <li>a volume (convex, ellipsoid...): the exact set of blocks inside the bounding cuboid.</li>
 * </ul>
 * Pure data: it never touches the world.
 */
public record Barrier(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Footprint footprint, Volume volume)
{
	public Barrier(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
	{
		this(name, world, minX, minY, minZ, maxX, maxY, maxZ, null, null);
	}

	public Barrier(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Footprint footprint)
	{
		this(name, world, minX, minY, minZ, maxX, maxY, maxZ, footprint, null);
	}

	/** Bounding box of the whole barrier (exact for a cuboid, a broad-phase box for a shaped barrier). */
	public BoundingBox box()
	{
		return new BoundingBox(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
	}

	public boolean isShaped()
	{
		return footprint != null || volume != null;
	}

	public boolean contains(int x, int y, int z)
	{
		if(x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
			return false;
		if(footprint != null && !footprint.contains(x, z))
			return false;
		return volume == null || volume.contains(x, y, z);
	}

	/** For a cell that is inside the barrier: is it on the outer surface? Only these cells ever need client-side collision. */
	public boolean isShell(int x, int y, int z)
	{
		if(!isShaped())
			return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
		return !contains(x + 1, y, z) || !contains(x - 1, y, z) || !contains(x, y + 1, z) || !contains(x, y - 1, z) || !contains(x, y, z + 1) || !contains(x, y, z - 1);
	}

	/** Does the box overlap (strictly) any cell of this barrier? */
	public boolean overlaps(BoundingBox other)
	{
		BoundingBox bounds = box();
		if(!bounds.overlaps(other))
			return false;
		if(!isShaped())
			return true;
		int x0 = (int) Math.floor(Math.max(other.getMinX(), bounds.getMinX())), x1 = (int) Math.ceil(Math.min(other.getMaxX(), bounds.getMaxX())) - 1;
		int y0 = (int) Math.floor(Math.max(other.getMinY(), bounds.getMinY())), y1 = (int) Math.ceil(Math.min(other.getMaxY(), bounds.getMaxY())) - 1;
		int z0 = (int) Math.floor(Math.max(other.getMinZ(), bounds.getMinZ())), z1 = (int) Math.ceil(Math.min(other.getMaxZ(), bounds.getMaxZ())) - 1;
		for(int x = x0; x <= x1; x++)
			for(int z = z0; z <= z1; z++)
				for(int y = y0; y <= y1; y++)
					if(contains(x, y, z))
						return true;
		return false;
	}

	public boolean isSingleBlock()
	{
		return !isShaped() && minX == maxX && minY == maxY && minZ == maxZ;
	}

	public String describe()
	{
		String range = world + " [" + minX + ", " + minY + ", " + minZ + "] -> [" + maxX + ", " + maxY + ", " + maxZ + "]";
		if(volume != null)
			return range + " (shaped, " + volume.cellCount() + " blocks)";
		if(footprint != null)
			return range + " (shaped, " + footprint.columnCount() + " columns)";
		if(isSingleBlock())
			return world + " [" + minX + ", " + minY + ", " + minZ + "]";
		return range;
	}
}
