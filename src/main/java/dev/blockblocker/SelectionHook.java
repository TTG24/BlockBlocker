package dev.blockblocker;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import org.bukkit.entity.Player;

/**
 * The only class that touches the WorldEdit/FAWE API. It is only loaded when one of them is installed.
 * Reads the player's //pos1, //pos2, //wand, //sel poly or //sel cyl selection.
 */
final class SelectionHook
{
	enum Kind
	{
		CUBOID, POLYGON, CYLINDER,
		/** Any other shape (ellipsoid, convex...): approximated by its bounding box. */
		BOUNDING_BOX
	}

	record Selection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Footprint footprint, Kind kind)
	{
	}

	private SelectionHook()
	{
	}

	/** @return the selection, or null when the player has nothing selected. */
	static Selection read(Player player)
	{
		LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(player));
		if(session == null)
			return null;

		RegionSelector selector = session.getRegionSelector(BukkitAdapter.adapt(player.getWorld()));

		// Complete selection of any type.
		try
		{
			final Region region = selector.getRegion();
			final BlockVector3 min = region.getMinimumPoint();
			final BlockVector3 max = region.getMaximumPoint();

			if(region instanceof Polygonal2DRegion || region instanceof CylinderRegion)
			{
				// Both are a flat shape extruded upward: ask WorldEdit which columns are inside, so its edge rules match exactly.
				Footprint fp = Footprint.build(min.x(), max.x(), min.z(), max.z(), (x, z) -> region.contains(BlockVector3.at(x, min.y(), z)));
				return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), fp, region instanceof Polygonal2DRegion ? Kind.POLYGON : Kind.CYLINDER);
			}

			Kind kind = region instanceof CuboidRegion ? Kind.CUBOID : Kind.BOUNDING_BOX;
			return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), null, kind);
		}
		catch(IncompleteRegionException ignored)
		{
			// Only one position set: that's a single block.
		}

		try
		{
			BlockVector3 p = selector.getPrimaryPosition();
			return new Selection(p.x(), p.y(), p.z(), p.x(), p.y(), p.z(), null, Kind.CUBOID);
		}
		catch(IncompleteRegionException ignored)
		{
			return null;
		}
	}
}
