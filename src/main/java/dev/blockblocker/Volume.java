package dev.blockblocker;

import java.util.ArrayList;
import java.util.List;

/**
 * Exactly which blocks a free-form 3D selection (convex, ellipsoid, ...) covers, stored as vertical runs per (x, z) column.
 * Built from WorldEdit's own contains() so the edge rules match exactly.
 */
final class Volume
{
	private static final int[] EMPTY = new int[0];

	/** Hard limit on the bounding box a Volume is built for, so one /block add can't freeze or flood the server. */
	static final long MAX_BOUNDING_CELLS = 8_000_000L;

	@FunctionalInterface
	interface Cell
	{
		boolean test(int x, int y, int z);
	}

	private final int minX, minZ, sizeX, sizeZ;
	private final int[][] columns; // index = (x - minX) * sizeZ + (z - minZ); each entry is y0,y1 pairs (inclusive, ascending)

	private Volume(int minX, int minZ, int sizeX, int sizeZ, int[][] columns)
	{
		this.minX = minX;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.columns = columns;
	}

	static long boundingCells(int minX, int maxX, int minY, int maxY, int minZ, int maxZ)
	{
		return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
	}

	static Volume build(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Cell inside)
	{
		int sizeX = maxX - minX + 1, sizeZ = maxZ - minZ + 1;
		int[][] cols = new int[sizeX * sizeZ][];
		for(int x = minX; x <= maxX; x++)
			for(int z = minZ; z <= maxZ; z++)
			{
				List<Integer> runs = new ArrayList<>();
				int start = Integer.MIN_VALUE;
				for(int y = minY; y <= maxY; y++)
				{
					boolean in = inside.test(x, y, z);
					if(in && start == Integer.MIN_VALUE)
						start = y;
					else if(!in && start != Integer.MIN_VALUE)
					{
						runs.add(start);
						runs.add(y - 1);
						start = Integer.MIN_VALUE;
					}
				}
				if(start != Integer.MIN_VALUE)
				{
					runs.add(start);
					runs.add(maxY);
				}
				cols[(x - minX) * sizeZ + (z - minZ)] = runs.isEmpty() ? EMPTY : runs.stream().mapToInt(Integer::intValue).toArray();
			}
		return new Volume(minX, minZ, sizeX, sizeZ, cols);
	}

	boolean contains(int x, int y, int z)
	{
		int dx = x - minX, dz = z - minZ;
		if(dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ)
			return false;
		int[] r = columns[dx * sizeZ + dz];
		for(int i = 0; i < r.length; i += 2)
			if(y >= r[i] && y <= r[i + 1])
				return true;
		return false;
	}

	long cellCount()
	{
		long n = 0;
		for(int[] r : columns)
			for(int i = 0; i < r.length; i += 2)
				n += r[i + 1] - r[i] + 1;
		return n;
	}

	/** One line per non-empty column: "x z y0 y1 y0 y1 ...". */
	List<String> encode()
	{
		List<String> out = new ArrayList<>();
		for(int dx = 0; dx < sizeX; dx++)
			for(int dz = 0; dz < sizeZ; dz++)
			{
				int[] r = columns[dx * sizeZ + dz];
				if(r.length == 0)
					continue;
				StringBuilder sb = new StringBuilder().append(minX + dx).append(' ').append(minZ + dz);
				for(int v : r)
					sb.append(' ').append(v);
				out.add(sb.toString());
			}
		return out;
	}

	static Volume decode(List<String> lines, int minX, int maxX, int minZ, int maxZ)
	{
		int sizeX = maxX - minX + 1, sizeZ = maxZ - minZ + 1;
		int[][] cols = new int[sizeX * sizeZ][];
		for(int i = 0; i < cols.length; i++)
			cols[i] = EMPTY;
		for(String line : lines)
		{
			String[] parts = line.trim().split("\\s+");
			int x = Integer.parseInt(parts[0]), z = Integer.parseInt(parts[1]);
			if(x < minX || x > maxX || z < minZ || z > maxZ)
				continue;
			int[] r = new int[parts.length - 2];
			for(int i = 2; i < parts.length; i++)
				r[i - 2] = Integer.parseInt(parts[i]);
			cols[(x - minX) * sizeZ + (z - minZ)] = r;
		}
		return new Volume(minX, minZ, sizeX, sizeZ, cols);
	}
}
