package dev.blockblocker;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.event.events.FlagEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * GrimAC doesn't model our client-side shulkers exactly (they are scaled and ride invisible displays), so right next to
 * them its movement simulation disagrees with the client and pulls the player back, which feels like lag. This ignores
 * Grim's movement flags only while a player is touching distance from one of those shulkers. Isolated in its own class
 * so BlockBlocker loads fine without GrimAC.
 */
final class GrimHook
{
	private GrimHook()
	{
	}

	static void register(JavaPlugin plugin, Predicate<UUID> nearCollider)
	{
		GrimAPIProvider.get().getEventBus().subscribe(plugin, FlagEvent.class, event -> {
			String check = event.getCheck().getCheckName();
			if(check == null || !check.toLowerCase(Locale.ROOT).matches(".*(simulation|prediction|phase|noslow|groundspoof|nofall).*"))
				return;
			if(nearCollider.test(event.getUser().getUniqueId()))
				event.setCancelled(true);
		});
	}
}
