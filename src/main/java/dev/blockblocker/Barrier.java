package dev.blockblocker;

import org.bukkit.util.BoundingBox;

/**
 * An invisible wall made of whole block cells. Either a plain cuboid (min/max inclusive), or - when footprint is set - that
 * cuboid's height range extruded over a polygon/cylinder footprint. Pure data: it never touches the world.
 */
public record Barrier(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Footprint footprint)
{
	public Barrier(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
	{
		this(name, world, minX, minY, minZ, maxX, maxY, maxZ, null);
	}

	/** Bounding box of the whole barrier (exact for a cuboid, a broad-phase box for a footprint shape). */
	public BoundingBox box()
	{
		return new BoundingBox(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
	}

	public boolean contains(int x, int y, int z)
	{
		if(x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
			return false;
		return footprint == null || footprint.contains(x, z);
	}

	/** For a cell that is inside the barrier: is it on the outer surface? Only these cells ever need client-side collision. */
	public boolean isShell(int x, int y, int z)
	{
		if(y == minY || y == maxY)
			return true;
		if(footprint == null)
			return x == minX || x == maxX || z == minZ || z == maxZ;
		return !footprint.contains(x + 1, z) || !footprint.contains(x - 1, z) || !footprint.contains(x, z + 1) || !footprint.contains(x, z - 1);
	}

	/** Does the box overlap (strictly) any cell of this barrier? */
	public boolean overlaps(BoundingBox other)
	{
		BoundingBox bounds = box();
		if(!bounds.overlaps(other))
			return false;
		if(footprint == null)
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
		return footprint == null && minX == maxX && minY == maxY && minZ == maxZ;
	}

	public String describe()
	{
		if(footprint != null)
			return world + " [" + minX + ", " + minY + ", " + minZ + "] -> [" + maxX + ", " + maxY + ", " + maxZ + "] (shaped, " + footprint.columnCount() + " columns)";
		if(isSingleBlock())
			return world + " [" + minX + ", " + minY + ", " + minZ + "]";
		return world + " [" + minX + ", " + minY + ", " + minZ + "] -> [" + maxX + ", " + maxY + ", " + maxZ + "]";
	}
}
