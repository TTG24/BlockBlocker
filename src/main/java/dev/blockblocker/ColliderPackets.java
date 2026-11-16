package dev.blockblocker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
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
	/** Debug: show the shulkers (glowing, visible through walls) instead of hiding them. */
	private static volatile boolean visible;

	static void setVisible(boolean v)
	{
		visible = v;
	}

	static boolean isVisible()
	{
		return visible;
	}

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
		spawnCube(player, entityId, x, y, z, 1);
	}

	/**
	 * Spawns an invisible shulker as a solid cube whose minimum corner is (x, y, z) and whose side is size (blocks).
	 * Smaller cubes are made with the scale attribute, which shrinks the hitbox too.
	 */
	static void spawnCube(Player player, int entityId, double x, double y, double z, double size)
	{
		var spawn = new WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.SHULKER,
				new Location(x + size / 2, y, z + size / 2, 0f, 0f), 0f, 0, Vector3d.zero());
		// Entity flags (index 0), bit 0x20 = invisible.
		var meta = new WrapperPlayServerEntityMetadata(entityId, List.of(new EntityData<>(0, EntityDataTypes.BYTE, visible ? (byte) 0x40 : (byte) 0x20)));
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, meta);
		if(size != 1)
			PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerUpdateAttributes(entityId,
					List.of(new WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, size, List.of()))));
	}

	/**
	 * Spawns (or re-spawns with the same ids) a stack of invisible shulker cubes of side size16 whose lowest cube's minimum
	 * corner is (x, y, z), in blocks. ids = [vehicle id, shulker ids...].
	 * <p>
	 * A shulker that isn't riding anything always snaps to the centre of its block on the client. A riding one is placed
	 * exactly where its vehicle says, so the first shulker rides an invisible block display (size 0: its passenger sits right
	 * at its position), and each further shulker rides the one below it (a shulker's passenger sits on its top), giving a
	 * solid stack at any position. A vehicle id of -1 means a plain whole-cell shulker, which needs no vehicle.
	 */
	static void spawnColumn(Player player, int[] ids, double x, double y, double z, double size)
	{
		var manager = PacketEvents.getAPI().getPlayerManager();
		// One bundle: the client applies all of it at once, so it never has a moment with a full-size shulker snapped to the
		// block centre (before it is shrunk and mounted), which would shove a player standing there.
		manager.sendPacket(player, new WrapperPlayServerBundle());
		try
		{
			if(ids[0] < 0)
			{
				spawnCube(player, ids[1], x, y, z, size);
				return;
			}
			double cx = x + size / 2, cz = z + size / 2;
			// An empty block display: it draws nothing and has no hitbox.
			manager.sendPacket(player, new WrapperPlayServerSpawnEntity(ids[0], UUID.randomUUID(), EntityTypes.BLOCK_DISPLAY,
					new Location(cx, y, cz, 0f, 0f), 0f, 0, Vector3d.zero()));
			for(int i = 1; i < ids.length; i++)
				spawnCube(player, ids[i], x, y + (i - 1) * size, z, size);
			manager.sendPacket(player, new WrapperPlayServerSetPassengers(ids[0], new int[] {ids[1]}));
			for(int i = 1; i < ids.length - 1; i++)
				manager.sendPacket(player, new WrapperPlayServerSetPassengers(ids[i], new int[] {ids[i + 1]}));
		}
		finally
		{
			manager.sendPacket(player, new WrapperPlayServerBundle());
		}
	}

	/** Re-sends only the visibility flag of an existing shulker (for /block shulkers), without re-spawning it. */
	static void sendFlags(Player player, int entityId)
	{
		PacketEvents.getAPI().getPlayerManager().sendPacket(player,
				new WrapperPlayServerEntityMetadata(entityId, List.of(new EntityData<>(0, EntityDataTypes.BYTE, visible ? (byte) 0x40 : (byte) 0x20))));
	}

	/** What BlockBlocker wants to know about clicks. Every method is called on the network thread: keep it thread-safe. */
	interface ClickHooks
	{
		/** Is this entity id one of our fake shulkers? */
		boolean isCollider(int entityId);

		/** The player clicked while in "/block pick" mode. Return true if it was consumed (the click is then cancelled). */
		boolean consumePick(Player player);

		/** A normal right-click landed on one of our fake shulkers (the real block behind it never saw it). */
		void colliderClicked(Player player, int entityId);

		/** A right-click on a block position (the client may be pointing at one of our fake barrier blocks). */
		void blockClicked(Player player, int x, int y, int z);

		/** The client attacked an entity. ours = it was one of our fake shulkers (so no real entity was hit). */
		void attackPacket(Player player, boolean ours, int entityId);

		/** The client swung its main arm. For a real entity hit this comes right after the attack packet. */
		void swingPacket(Player player);
	}

	/**
	 * A click on a fake shulker never becomes a Bukkit event (the server has no such entity), and a click on a fake barrier
	 * block lands on a block that is really air. Both are read from the packet so BlockBlocker can use them.
	 */
	static void listenForClicks(ClickHooks hooks)
	{
		PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract()
		{
			@Override
			public void onPacketReceive(PacketReceiveEvent event)
			{
				Object type = event.getPacketType();
				Object who = event.getPlayer();
				if(!(who instanceof Player player))
					return;

				if(type == PacketType.Play.Client.INTERACT_ENTITY)
				{
					var packet = new WrapperPlayClientInteractEntity(event);
					boolean ours = hooks.isCollider(packet.getEntityId());
					if(packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK)
						hooks.attackPacket(player, ours, packet.getEntityId());
					if(!ours)
						return;
					if(hooks.consumePick(player))
					{
						event.setCancelled(true);
						return;
					}
					// A right-click sends INTERACT_AT then INTERACT; act on only one of them, for the main hand.
					if(packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT && packet.getHand() == InteractionHand.MAIN_HAND)
						hooks.colliderClicked(player, packet.getEntityId());
				}
				else if(type == PacketType.Play.Client.ANIMATION)
				{
					if(new WrapperPlayClientAnimation(event).getHand() == InteractionHand.MAIN_HAND)
						hooks.swingPacket(player);
				}
				else if(type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)
				{
					if(hooks.consumePick(player))
					{
						event.setCancelled(true);
						return;
					}
					var packet = new WrapperPlayClientPlayerBlockPlacement(event);
					if(packet.getHand() == InteractionHand.MAIN_HAND)
						hooks.blockClicked(player, packet.getBlockPosition().getX(), packet.getBlockPosition().getY(), packet.getBlockPosition().getZ());
				}
			}
		});
	}

	static void destroy(Player player, int[] entityIds)
	{
		if(entityIds.length == 0)
			return;
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerDestroyEntities(entityIds));
	}
}
