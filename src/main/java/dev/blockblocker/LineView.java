package dev.blockblocker;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Straight, solid lines shown to one player only (like WorldEdit CUI outlines, but without a client mod): each line is a
 * thin, stretched, full-bright block display that only that player can see. Lines stay in place and are only spawned or
 * removed when the set of lines changes, so nothing flickers or piles up like particles do.
 */
final class LineView
{
	/** How a line looks: its colour (a plain block) and thickness in blocks. */
	enum Style
	{
		AREA(Material.WHITE_CONCRETE, 0.03f, Color.WHITE),
		CUBE(Material.MAGENTA_CONCRETE, 0.018f, Color.fromRGB(255, 60, 220)),
		GRID(Material.LIGHT_BLUE_CONCRETE, 0.008f, Color.fromRGB(90, 170, 255)),
		PREVIEW(Material.LIME_CONCRETE, 0.025f, Color.fromRGB(60, 255, 90)),
		REMOVE(Material.RED_CONCRETE, 0.03f, Color.fromRGB(255, 40, 40)),
		/** /block show outlines: bright red. */
		SHOW(Material.REDSTONE_BLOCK, 0.025f, Color.fromRGB(255, 0, 0));

		final BlockData block;
		final float thickness;
		final Color glow;

		Style(Material m, float thickness, Color glow)
		{
			this.block = m.createBlockData();
			this.thickness = thickness;
			this.glow = glow;
		}
	}

	/** One axis-aligned line (coordinates rounded to 1/4096 so the same line is recognised again). */
	record Line(long x0, long y0, long z0, long x1, long y1, long z1, Style style)
	{
		private static final double Q = 4096;

		static Line of(double x0, double y0, double z0, double x1, double y1, double z1, Style style)
		{
			return new Line(Math.round(x0 * Q), Math.round(y0 * Q), Math.round(z0 * Q), Math.round(x1 * Q), Math.round(y1 * Q), Math.round(z1 * Q), style);
		}
	}

	/** Adds the 12 edges of a box {minX, minY, minZ, maxX, maxY, maxZ}. */
	static void box(Set<Line> out, double[] b, Style style)
	{
		double[] xs = {b[0], b[3]}, ys = {b[1], b[4]}, zs = {b[2], b[5]};
		for(double y : ys)
			for(double z : zs)
				out.add(Line.of(b[0], y, z, b[3], y, z, style));
		for(double x : xs)
			for(double z : zs)
				out.add(Line.of(x, b[1], z, x, b[4], z, style));
		for(double x : xs)
			for(double y : ys)
				out.add(Line.of(x, y, b[2], x, y, b[5], style));
	}

	private final JavaPlugin plugin;
	private final Map<Line, BlockDisplay> shown = new HashMap<>();

	LineView(JavaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Makes the player see exactly these lines: removes the ones no longer wanted, spawns the new ones. Main thread. */
	void show(Player player, Set<Line> wanted)
	{
		Iterator<Map.Entry<Line, BlockDisplay>> it = shown.entrySet().iterator();
		while(it.hasNext())
		{
			Map.Entry<Line, BlockDisplay> e = it.next();
			if(!wanted.contains(e.getKey()) || !e.getValue().isValid() || e.getValue().getWorld() != player.getWorld())
			{
				e.getValue().remove();
				it.remove();
			}
		}
		for(Line l : wanted)
			if(!shown.containsKey(l))
			{
				BlockDisplay d = spawn(player, l);
				if(d != null)
					shown.put(l, d);
			}
	}

	/** Removes every line. */
	void clear()
	{
		for(BlockDisplay d : shown.values())
			d.remove();
		shown.clear();
	}

	private BlockDisplay spawn(Player player, Line l)
	{
		World w = player.getWorld();
		double q = Line.Q;
		float t = l.style().thickness;
		double x0 = l.x0() / q, y0 = l.y0() / q, z0 = l.z0() / q;
		// Thickness t around the line, and t longer so the corners close neatly.
		float sx = (float) ((l.x1() - l.x0()) / q) + t, sy = (float) ((l.y1() - l.y0()) / q) + t, sz = (float) ((l.z1() - l.z0()) / q) + t;
		Location at = new Location(w, x0 - t / 2, y0 - t / 2, z0 - t / 2);
		if(!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
			return null;
		BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
			e.setVisibleByDefault(false); // only the editing player sees it (shown below)
			e.setPersistent(false); // never saved with the world
			e.setBlock(l.style().block);
			e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f()));
			e.setBrightness(new Display.Brightness(15, 15));
			e.setShadowRadius(0);
			e.setShadowStrength(0);
			// Glowing: a bright outline in the line's colour, also visible through blocks (like WorldEdit CUI).
			e.setGlowing(true);
			e.setGlowColorOverride(l.style().glow);
		});
		player.showEntity(plugin, d);
		Perf.PARTICLES.incrementAndGet();
		return d;
	}
}
