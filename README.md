# BlockBlocker

A simple Purpur/Paper plugin that stops players from walking through an area **without placing, changing or removing anything in the world**.

Select an area with WorldEdit / FastAsyncWorldEdit, run `/block`, and players can no longer pass through it. The wall is invisible and exists only as coordinates the plugin checks, so it never touches blocks, items or entities, and nothing is written to the world.

It was built for cases where a normal barrier block won't do, such as sealing the gaps between fences and cobblestone walls (those cells already contain a block, so a barrier block can't go there).

## Features

- **Uses your WorldEdit / FAWE selection**
  - `//pos1` only or `//pos2` only → a single block
  - `//pos1` + `//pos2` (or `//wand`) → the whole cuboid
  - `//sel poly` and `//sel cyl` → follow the real shape, not the box around it
  - `//sel convex`, `//sel sphere`, `//sel ellipsoid` and other free-form shapes → follow the real shape too (the exact blocks inside are stored). If the shape's bounding box is extremely large (over 8 million blocks) it falls back to the bounding box and tells you.
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
| `/block` or `/block add [name]` | Block off your current selection with plain full blocks: every cell is blocked whole, including cells with blocks smaller than a full cube (iron bars, fences, panes, walls, slabs, stairs...), which get a full-size shulker. Re-using a name replaces that barrier. |
| `/block addshape [name]` | The same, but blocks smaller than a full block keep their shape: iron bars, fences, panes and walls keep their own collision, doors, trapdoors, slabs, stairs... keep their footprint over the whole cell height, and the air above/below any of them copies that shape instead of being a full block (config `shape-copy-partial-blocks`). Short form `as`. |
| `/block shapes <name> [on\|off]` | Switch that shape copying on or off for an existing barrier (no on/off shows the current setting). Barriers made before this option existed have it on. |
| `/block remove <name>` | Remove a barrier. |
| `/block edge <name> <north\|south\|east\|west\|me\|off>` | Turn a barrier into a thin wall (4/16 of a block) on one side of each of its cells, instead of filling them. `me` = the side you are looking toward. Works even inside a cell that holds a block (e.g. the far edge of a fence's own block): the block stays visible. `off` makes it fill its cells again. |
| `/block custom [name]` (`/bb c`) | Build a custom barrier **visually** in your selection (`//pos1` + `//pos2`, or the block you stand in). No name = the next free number, like `/block add`. You see the area outline and a grid on the cell face you look at (each cell has its own grid). **Right-click** places a cube one grid square in size (just inside the face, or against the cube or real block you look at, like placing blocks), **left-click** removes the cube you look at. **Sneak + right-click** changes the grid (1, 2, 4, 8 or 16 squares per block side; or `/bb c grid <n>`). **Sneak + left-click** switches between `single`, `vertical` and `horizontal` (or `/bb c mode <...>`): in a line mode one click places/removes a whole straight line of cubes across the area (horizontal runs along the face you look at, or the way you look on a floor/ceiling). Green outline = what a right-click adds, red = what a left-click removes. Hold any item to right-click at empty air. Type `done` in chat (or `/bb c done`) to keep the changes and stop; `cancel` in chat (or `/bb c cancel`) undoes everything since you started (a barrier made in that session is removed). Changes are saved as you go; an existing custom barrier is edited in its own area. Giving the name of an already made barrier opens it in the editor starting from its exact current shape (up to 4096 cells); `cancel` turns it back into a normal barrier. |
| `/block custom <name> <offX> <offY> <offZ> <sizeX> <sizeY> <sizeZ>` | A barrier that is one exact box, decimals allowed. Offsets are from the lowest corner of the cell you selected with `//pos1` (or the block you stand in without a selection). Example: `/block custom gap 0.4 0 0 0.2 1 1` is a 0.2-thick wall through the middle of that cell. Run it again with the same name to move/resize it; `/block remove <name>` removes it. Short form `cu`. |
| `/block list` | List all barriers and where they are. |
| `/block tp <name>` | Teleport above a barrier. |
| `/block show [name...]` | Toggle red particle outlines of nearby barriers (only you see them). Give one or more names to outline only those, e.g. `/block show 3 door1`; run it with the same names, or plain `/block show`, to hide them. |
| `/block pick` | Then right-click a barrier to see its name. The click is cancelled, so it won't activate a sign or block. Chat shows clickable **[Switch off]** / **[Switch on]** buttons. Shortcut: right-click while holding a **bone** (config `pick-item`) does the same, every time, without the command. |
| `/block info` | Name of the barrier you are looking at (no click needed). |
| `/block debug` | Toggle a diagnostic mode for yourself: every sword swing near a barrier prints, in chat, what the plugin did with it and why it stopped (for troubleshooting `attack-through`). |
| `/block shulkers` | Debug: make the invisible shulkers visible and glowing (even through walls) for everyone near a barrier, so you can see exactly where they are. Run again to hide them. Short form `sh`. Lasts until the next restart. |
| `/block off [name] [seconds]` | Switch a barrier off for a while (default `disable-seconds`; `0` = until `/block on`). With no name, uses the one you picked last. |
| `/block on [name]` | Switch it back on. |
| `/block clear` | Remove every barrier. |
| `/block reload` | Re-read `data.yml` and the config. |

Alias: `/bb`.

Short forms: `a` (add), `rm` (remove), `ls` or `l` (list), `t` (tp), `s` (show), `p` (pick), `i` (info), `cl` (clear), `rl` (reload). For example, `/block rm 1` or `/bb ls`. Tab-completion works with them.

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
| `thin-blocks` | `[BARS, GLASS_PANE, FENCE, WALL]` | Blocks that keep their own thin vanilla collision inside a barrier, and whose exact shape is copied into the barrier's air cells above/below them (3 iron bars + 2 barrier cells above behave like 5 tall bars). The copy is built from tiny invisible shulkers (needs `shulker-colliders: true`), so the client collides with it itself. Use `[]` to make every block fully solid for its whole cell instead. MZ barrier cells always stay full-cell. |
| `connect-shapes` | `true` | In one barrier, the copies above/below bars, fences and walls join up with their thin neighbours (an arm as thick as the post), so a selection over a row of bars is one continuous thin wall with no gaps between posts. |
| `max-shape-colliders` | `1500` | Most tiny shulkers sent to one player for thin shapes and edge barriers (nearest first). Beyond it the server alone enforces them. |
| `shape-fake-blocks` | `false` | Alternative to the tiny shulkers for the air above/below bars, fences and walls. The client is sent real, invisible blocks with the same thin collision as the bars/fence/wall below, so it collides by itself. **Needs the resource pack in `resourcepack/`** (otherwise players see the fake blocks). Not used in push mode. |
| `fake-pane`, `fake-fence`, `fake-wall` | `MAGENTA_STAINED_GLASS_PANE`, `CHERRY_FENCE`, `RESIN_BRICK_WALL` | The blocks used for it. The pack draws them as nothing, so never use them in your maps. |
| `shape-mode` | `block` | What the copied thin shape above bars/fences/panes/walls does, enforced by the server only: `block` = the server stops players at the exact thin shape (can feel like a small pull-back); `push` = not solid, a player touching it is smoothly shoved away. |
| `push-strength` | `0.5` | How hard `push` shoves, in blocks per tick. |
| `block-fill-cell` | `false` | In `block` mode, air cells above bars/fences/panes/walls are a plain solid cell, so there is no gap above them anywhere. `false` = copy the thin shape with tiny shulkers, which leaves the same gaps the real blocks have. |
| `push-fill-cell` | `false` | In `push` mode, the whole cell above the bars pushes players out, so there is no gap between the thin pieces to stand in. `false` = only the thin shape pushes. |
| `mz-barriers` | `false` | Give the **[MinecraftZombies](https://github.com/TTG24/MZ-Minecraft-Zombies)** zombie-window barriers the same client-side collision: fake barrier blocks where a broken barrier is now empty, and (with `shulker-colliders`) invisible shulkers on non-full barrier blocks. MZ keeps blocking players on the server itself; this only removes the pulled-back rubber-banding. Applies only to players in an MZ game, in that game's world. Needs the MinecraftZombies plugin; `/block reload` applies a change. |
| `attack-through` | `true` | A fake barrier block or shulker can swallow a sword swing aimed at a creature behind it (such as through an empty window). With this on, that swing is passed on to the creature really in the player's line of sight, within their normal reach and never at players, as a normal attack. Blocks standing inside a barrier cell (boards, half blocks, fences, bars, barrier blocks) never protect the creature; ordinary blocks elsewhere still do. Needs `shulker-colliders: true`. |
| `attack-through-full-blocks` | `true` | Whether full solid blocks inside a barrier cell (e.g. whole planks) are also passed through. Set `false` to let only non-full blocks through. |
| `click-through` | `true` | When a player right-clicks one of the invisible barriers instead of the real block behind it (for example a sign that buys a gun), pass the click through to that real block so plugins react as if it had been clicked directly. Needs `shulker-colliders: true`. Only plugin reactions are passed on; vanilla actions like opening a chest are not. |
| `disable-seconds` | `60` | How long `/block off` lasts before the barrier switches itself back on (`0` = until `/block on`). Switched-off barriers are never saved: a restart or `/block reload` turns them all back on. |
| `shulker-colliders` | `false` | **Experimental.** Client-side invisible shulkers make non-full blocks fully solid without hiding them. **Needs a server restart** to change. |

Barriers are stored in `plugins/BlockBlocker/data.yml`.

## How it works

- A barrier is a set of whole block cells: a cuboid, a polygon/cylinder footprint between two heights, or the exact blocks of a free-form shape such as convex.
- Each movement packet is checked against the barriers in the player's world. If the move would enter one, the player is slid along the wall instead of being stopped dead. Both axis orders are tried, to agree with how the Minecraft client resolves its own collisions.
- Thin shapes (bar/fence copies, edge barriers) use tiny invisible shulkers, each as thick as the shape and spaced with gaps under half a block (too small for any player, even prone). A lone shulker always snaps to the centre of its block on the client, so each stack of them rides an invisible block display, which can be placed anywhere.
- To avoid the server "pulling players back", nearby clients are shown the wall: fake barrier blocks in air cells, and (optionally) invisible shulkers in cells with non-full blocks. Both are packets sent to individual players. The server never creates a block or entity for them, and they are restored when you walk away, remove the barrier, or stop the server.

## Limitations

- Barriers stop **players only**, not mobs.
- Players riding boats, horses or minecarts are not stopped yet.
- A barrier that is only one block tall can be jumped over, like any one-block wall. Make it taller.
- With shulker colliders on, a player's crosshair may target the invisible shulker instead of the block behind it, which would normally stop players using blocks (such as signs) inside a barrier cell. `click-through` (on by default) passes such a click on to the real block behind the barrier, within 5 blocks, so plugin sign shops keep working; vanilla actions like opening a chest are not performed. `/block pick` and `/block off` are a manual fallback: find the barrier, switch it off for a while, use the block, and it switches itself back on. Switching off applies to everyone in that world, not just you.
- Free-form shapes (convex, ellipsoid…) store every block inside them, so very large ones make a bigger `data.yml` and take a moment to create. Beyond 8 million blocks of bounding box they fall back to the bounding box.
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

## Related projects

- [MZ-Minecraft-Zombies](https://github.com/TTG24/MZ-Minecraft-Zombies): the MinecraftZombies server plugin (round-based zombie arenas).
- [MZ-Movement](https://github.com/TTG24/MZ-Movement): the Fabric client mod (tactical movement, zombie animations, crawlers, gore).
