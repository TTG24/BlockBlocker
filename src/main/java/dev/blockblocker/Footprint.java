package dev.blockblocker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Which (x, z) columns a flat-shape selection (polygon, cylinder) covers, stored as sorted runs per x. The barrier
 * extrudes it between its min and max Y. Built from WorldEdit's own contains() so the edge rules match exactly.
 */
final class Footprint
{
	private final int minX;
	private final int[][] runs; // index = x - minX; each entry is z0,z1 pairs (inclusive, ascending)

	private Footprint(int minX, int[][] runs)
	{
		this.minX = minX;
		this.runs = runs;
	}

	static Footprint build(int minX, int maxX, int minZ, int maxZ, BiPredicate<Integer, Integer> inside)
	{
		int[][] runs = new int[maxX - minX + 1][];
		for(int x = minX; x <= maxX; x++)
		{
			List<Integer> list = new ArrayList<>();
			int start = Integer.MIN_VALUE;
			for(int z = minZ; z <= maxZ; z++)
			{
				boolean in = inside.test(x, z);
				if(in && start == Integer.MIN_VALUE)
					start = z;
				else if(!in && start != Integer.MIN_VALUE)
				{
					list.add(start);
					list.add(z - 1);
					start = Integer.MIN_VALUE;
				}
			}
			if(start != Integer.MIN_VALUE)
			{
				list.add(start);
				list.add(maxZ);
			}
			runs[x - minX] = list.stream().mapToInt(Integer::intValue).toArray();
		}
		return new Footprint(minX, runs);
	}

	boolean contains(int x, int z)
	{
		int idx = x - minX;
		if(idx < 0 || idx >= runs.length)
			return false;
		int[] r = runs[idx];
		for(int i = 0; i < r.length; i += 2)
			if(z >= r[i] && z <= r[i + 1])
				return true;
		return false;
	}

	int columnCount()
	{
		int n = 0;
		for(int[] r : runs)
			for(int i = 0; i < r.length; i += 2)
				n += r[i + 1] - r[i] + 1;
		return n;
	}

	/** One line per x that has any columns: "x z0 z1 z0 z1 ...". */
	List<String> encode()
	{
		List<String> out = new ArrayList<>();
		for(int i = 0; i < runs.length; i++)
		{
			if(runs[i].length == 0)
				continue;
			StringBuilder sb = new StringBuilder().append(minX + i);
			for(int v : runs[i])
				sb.append(' ').append(v);
			out.add(sb.toString());
		}
		return out;
	}

	static Footprint decode(List<String> lines, int minX, int maxX)
	{
		int[][] runs = new int[maxX - minX + 1][];
		for(int i = 0; i < runs.length; i++)
			runs[i] = new int[0];
		for(String line : lines)
		{
			String[] parts = line.trim().split("\\s+");
			int x = Integer.parseInt(parts[0]);
			if(x < minX || x > maxX)
				continue;
			int[] r = new int[parts.length - 1];
			for(int i = 1; i < parts.length; i++)
				r[i - 1] = Integer.parseInt(parts[i]);
			runs[x - minX] = r;
		}
		return new Footprint(minX, runs);
	}
}
