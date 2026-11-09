package dev.blockblocker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The only class that touches PacketEvents (bundled and relocated into our jar). It sends an invisible shulker to ONE
 * player's client, purely as a packet: the server never creates an entity, so no real entity exists anywhere.
 * A closed shulker is exactly 1x1x1 and solid to the player's client, so the client collides with a full cell.
 */
final class ColliderPackets
{
	private static boolean loaded;
	private static boolean ready;

	private ColliderPackets()
	{
	}

	/** Call from onLoad. @return false if PacketEvents could not be set up (the plugin keeps working without colliders). */
	static boolean load(JavaPlugin plugin)
	{
		try
		{
			PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
			PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(false);
			PacketEvents.getAPI().load();
			loaded = true;
		}
		catch(Throwable t)
		{
			plugin.getLogger().log(Level.WARNING, "PacketEvents could not be loaded; shulker colliders are disabled.", t);
		}
		return loaded;
	}

	/** Call from onEnable. */
	static boolean init(JavaPlugin plugin)
	{
		if(!loaded)
			return false;
		try
		{
			PacketEvents.getAPI().init();
			ready = true;
		}
		catch(Throwable t)
		{
			plugin.getLogger().log(Level.WARNING, "PacketEvents could not start; shulker colliders are disabled.", t);
		}
		return ready;
	}

	static void terminate()
	{
		if(ready)
			PacketEvents.getAPI().terminate();
		ready = false;
	}

	static int newEntityId()
	{
		return SpigotReflectionUtil.generateEntityId();
	}

	/** Spawns (or re-spawns, if the id already exists on the client) an invisible 1x1x1 shulker filling the block cell. */
	static void spawn(Player player, int entityId, int x, int y, int z)
	{
		var spawn = new WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.SHULKER,
				new Location(x + 0.5, y, z + 0.5, 0f, 0f), 0f, 0, Vector3d.zero());
		// Entity flags (index 0), bit 0x20 = invisible.
		var meta = new WrapperPlayServerEntityMetadata(entityId, List.of(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20)));
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, meta);
	}

	static void destroy(Player player, int[] entityIds)
	{
		if(entityIds.length == 0)
			return;
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerDestroyEntities(entityIds));
	}
}
