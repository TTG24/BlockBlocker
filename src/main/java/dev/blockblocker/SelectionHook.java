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
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import org.bukkit.entity.Player;

/**
 * The only class that touches the WorldEdit/FAWE API. It is only loaded when one of them is installed.
 * Reads the player's //pos1, //pos2, //wand, //sel poly, //sel cyl, //sel convex, //sel sphere... selection.
 */
final class SelectionHook
{
	enum Kind
	{
		CUBOID, POLYGON, CYLINDER,
		/** Any other shape (convex, ellipsoid...): the exact blocks inside are stored. */
		SHAPE,
		/** A free-form shape whose bounding box was too large to store exactly: approximated by its bounding box. */
		BOUNDING_BOX
	}

	record Selection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Footprint footprint, Volume volume, Kind kind)
	{
	}

	private SelectionHook()
	{
	}

	/** Makes the box the player's WorldEdit selection (//pos1 and //pos2), so they can adjust it and apply it back. */
	static void select(Player player, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
	{
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
		com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
		CuboidRegionSelector selector = new CuboidRegionSelector(world, BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ));
		session.setRegionSelector(world, selector);
		session.dispatchCUISelection(BukkitAdapter.adapt(player));
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
				return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), fp, null, region instanceof Polygonal2DRegion ? Kind.POLYGON : Kind.CYLINDER);
			}

			if(region instanceof CuboidRegion)
				return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), null, null, Kind.CUBOID);

			// Any other shape (convex, ellipsoid...): ask WorldEdit which blocks are inside, unless that would be too big.
			if(Volume.boundingCells(min.x(), max.x(), min.y(), max.y(), min.z(), max.z()) > Volume.MAX_BOUNDING_CELLS)
				return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), null, null, Kind.BOUNDING_BOX);
			Volume volume = Volume.build(min.x(), max.x(), min.y(), max.y(), min.z(), max.z(), (x, y, z) -> region.contains(BlockVector3.at(x, y, z)));
			return new Selection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), null, volume, Kind.SHAPE);
		}
		catch(IncompleteRegionException ignored)
		{
			// Only one position set: that's a single block.
		}

		try
		{
			BlockVector3 p = selector.getPrimaryPosition();
			return new Selection(p.x(), p.y(), p.z(), p.x(), p.y(), p.z(), null, null, Kind.CUBOID);
		}
		catch(IncompleteRegionException ignored)
		{
			return null;
		}
	}
}
