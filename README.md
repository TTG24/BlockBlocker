# BlockBlocker

A simple Purpur/Paper plugin that stops players from walking through an area **without placing, changing or removing anything in the world**.

Select an area with WorldEdit / FastAsyncWorldEdit, run `/block`, and players can no longer pass through it. The wall is invisible and exists only as coordinates the plugin checks, so it never touches blocks, items or entities, and nothing is written to the world.

It was built for cases where a normal barrier block won't do, such as sealing the gaps between fences and cobblestone walls (those cells already contain a block, so a barrier block can't go there).

## Features

- **Uses your WorldEdit / FAWE selection**
  - `//pos1` only or `//pos2` only → a single block
  - `//pos1` + `//pos2` (or `//wand`) → the whole cuboid
  - `//sel poly` and `//sel cyl` → follow the real shape, not the box around it
  - any other shape (ellipsoid, convex, …) → its bounding box (the plugin tells you)
- **Smooth, no rubber-banding.** Nearby players' clients are sent fake invisible barrier blocks (packets only) in empty cells, so their own game collides with the wall instead of being pulled back by the server.
- **Optional shulker colliders (experimental).** For cells that already hold a non-full block (fence, wall, slab, grass, water…), an invisible client-side shulker makes the whole cell solid while the block stays visible. See [Config](#config).
- **Server-side enforcement** as a backup, including fast movement (elytra, riptide) and ender pearls / chorus fruit.
- **Players can walk out** if a barrier is created around them.
- **Arena-instance support.** Other plugins can copy a world's barriers into a temporary world (see [For plugin developers](#for-plugin-developers)).
- `/block show` draws red particle outlines so you can see where barriers are. Only you see them.

## Requirements

- Purpur or Paper (built and tested against Minecraft **26.3**)
- Java **25**
- **WorldEdit or FastAsyncWorldEdit** to make selections (the plugin still loads without them, but `/block add` needs one)

PacketEvents is bundled in the jar, so there is nothing extra to install.

## Installation

1. Build the jar (see [Building](#building)) or download one from the releases page.
2. Put `BlockBlocker-<version>.jar` in your server's `plugins/` folder.
3. Restart the server.

## Usage

| Command | What it does |
|---|---|
| `/block` or `/block add [name]` | Block off your current selection. Re-using a name replaces that barrier. |
| `/block remove <name>` | Remove a barrier. |
| `/block list` | List all barriers and where they are. |
| `/block tp <name>` | Teleport above a barrier. |
| `/block show` | Toggle red particle outlines of nearby barriers (only you see them). |
| `/block clear` | Remove every barrier. |
| `/block reload` | Re-read `data.yml` and the config. |

Alias: `/bb`.

Make your selection first (`//pos1`, `//pos2`, `//wand`, `//sel poly`, `//sel cyl`), then run `/block add`.

### Permissions

| Permission | Default | Purpose |
|---|---|---|
| `blockblocker.admin` | op | Use `/block` |
| `blockblocker.bypass` | nobody (not even ops) | Walk through barriers |

## Config

`plugins/BlockBlocker/config.yml`:

| Key | Default | Meaning |
|---|---|---|
| `client-blocks` | `true` | Send fake invisible barrier blocks to nearby clients (packets only, only into empty cells). Removes rubber-banding. |
| `respect-partial-blocks` | `true` | Cells with a fence, slab, stairs, chest… use that block's own collision instead of a full wall. Ignored while shulker colliders are on. |
| `shulker-colliders` | `false` | **Experimental.** Client-side invisible shulkers make non-full blocks fully solid without hiding them. **Needs a server restart** to change. |

Barriers are stored in `plugins/BlockBlocker/data.yml`.

## How it works

- A barrier is a set of whole block cells (a cuboid, or a polygon/cylinder footprint between two heights).
- Each movement packet is checked against the barriers in the player's world. If the move would enter one, the player is slid along the wall instead of being stopped dead. Both axis orders are tried, to agree with how the Minecraft client resolves its own collisions.
- To avoid the server "pulling players back", nearby clients are shown the wall: fake barrier blocks in air cells, and (optionally) invisible shulkers in cells with non-full blocks. Both are packets sent to individual players. The server never creates a block or entity for them, and they are restored when you walk away, remove the barrier, or stop the server.

## Limitations

- Barriers stop **players only**, not mobs.
- Players riding boats, horses or minecarts are not stopped yet.
- A barrier that is only one block tall can be jumped over, like any one-block wall. Make it taller.
- With shulker colliders on, a player's crosshair may target the invisible shulker instead of the block behind it, which can make it awkward to interact with blocks inside a barrier cell.
- Shapes other than cuboid, polygon and cylinder use their bounding box.
- Shulker colliders are experimental. They were tested on Purpur 26.3 with fences and cobblestone walls; other block types may behave differently.

## For plugin developers

BlockBlocker barriers are not blocks, so copying an area with WorldEdit/FAWE does not copy them. If your plugin creates temporary worlds (for example copies of an arena at the same coordinates), call this on the BlockBlocker plugin instance (main thread):

```java
int copyBarriers(String fromWorld, String toWorld)
```

It copies the saved barriers of `fromWorld` into `toWorld` as **temporary** barriers: they are never written to `data.yml` and are dropped automatically when `toWorld` unloads. It can be called through reflection, so your plugin needs no compile-time dependency:

```java
Plugin bb = Bukkit.getPluginManager().getPlugin("BlockBlocker");
if (bb != null && bb.isEnabled()) {
    bb.getClass().getMethod("copyBarriers", String.class, String.class)
      .invoke(bb, templateWorld.getName(), instanceWorld.getName());
}
```

Add `BlockBlocker` to your `softdepend` so it loads first.

## Building

Requires JDK 25 (Gradle can download one automatically).

```
./gradlew build        # Windows: gradlew.bat build
```

The jar is written to `build/libs/BlockBlocker-<version>.jar` (it includes PacketEvents).

## License

BlockBlocker's own source code is released under the [MIT License](LICENSE).

The built jar also bundles **PacketEvents**, which is licensed under **GPL-3.0**. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for what that means if you redistribute a built jar.

## Credits

- Author: [TTG24](https://github.com/TTG24)
- [PacketEvents](https://github.com/retrooper/packetevents) by retrooper and contributors, used for the optional client-side shulker colliders.
- [Adventure](https://github.com/KyoriPowered/adventure) by KyoriPowered, a PacketEvents dependency.
