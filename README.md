# BlockBlocker

Invisible walls for Paper/Purpur servers. Select an area with WorldEdit or FastAsyncWorldEdit, run `/block`, and
players (and mobs) can no longer pass through it. **Nothing is placed in the world.** A barrier exists only as
coordinates the plugin checks, so it never touches blocks, items or entities.

It is made for spots where a barrier block won't fit, such as the gaps between fences and cobblestone walls, where
the cell already holds a block. Jars are on the [releases page](https://github.com/TTG24/BlockBlocker/releases).

## Features

- **Follows your selection.** Single blocks, cuboids, and the real shape of `//sel poly`, `cyl`, `convex`,
  `sphere` and other free-form selections.
- **No rubber-banding.** Nearby players are sent fake invisible blocks (packets only), so their own game collides
  with the wall instead of the server pulling them back. The server still enforces the wall as a backup, including
  elytra, riptide, ender pearls and chorus fruit.
- **Thin and shaped walls.** Keep the shape of bars, fences, panes, walls, doors and slabs, make a one-sided thin
  wall (`edge`), or draw a custom barrier cube by cube in-game.
- **Stops mobs too.** Zombies and other mobs are kept out, and a mob stuck against a barrier is steered around it.
- **Built for [MinecraftZombies](https://github.com/TTG24/MZ-Minecraft-Zombies).** Barriers are copied into MZ's
  arena copies, MZ's zombie windows can get the same smooth collision, and sword hits and sign clicks pass through
  the invisible wall to what is behind it.
- **Easy to check.** `/block show` outlines barriers in red (only you see them), and right-clicking with a bone
  shows a barrier's name, type and buttons to switch or edit it.

## Requirements

- Paper or Purpur, Minecraft **26.3**, Java **25**
- **WorldEdit or FastAsyncWorldEdit** to make selections
- Optional: [GrimAC](https://modrinth.com/plugin/grimac) (BlockBlocker tells it to ignore the invisible colliders)

PacketEvents is bundled in the jar, so there is nothing else to install.

## Installation

1. Download `BlockBlocker-<version>.jar` from the [releases page](https://github.com/TTG24/BlockBlocker/releases),
   or [build it](#building).
2. Put it in `plugins/` and restart the server.

## Commands

Make a selection first (`//pos1`, `//pos2`, `//wand`, `//sel poly`...), then run a command. `/block` has the
alias `/bb`, and most commands have a short form (shown in brackets).

| Command | What it does |
|---|---|
| `/block add [name]` (`/block`, `a`) | Block off the selection. Every cell is solid, small blocks included. Re-using a name replaces that barrier. |
| `/block addshape [name]` (`as`) | Same, but bars, fences, panes, walls, doors, slabs and stairs keep their own shape, and the air above and below copies it. |
| `/block shapes <name> [on\|off]` | Switch an existing barrier between plain and follow-shape blocking. |
| `/block connect <name> <side> [on\|off]` (`conn`) | Choose which sides a follow-shape barrier's copied shapes connect to. |
| `/block edge <name> <north\|south\|east\|west\|me\|off>` | Make a barrier a thin wall on one side of its cells. |
| `/block custom [name]` (`cu`, `c`) | Build a barrier visually: right-click places a cube, left-click removes one, sneak-click changes the grid or line mode, type `done` or `cancel` in chat. |
| `/block custom <name> <offX> <offY> <offZ> <sizeX> <sizeY> <sizeZ>` | One exact box (decimals allowed), measured from the selected cell. |
| `/block edit <name> <select\|apply\|expand\|contract\|shift\|rename>` (`e`) | Resize, move, rename or replace a barrier. `/block edit` explains each action. |
| `/block remove <name>` (`rm`) | Remove a barrier. `/block clear` (`cl`) removes all of them. |
| `/block list` (`ls`) / `/block tp <name>` (`t`) | List barriers / teleport above one. |
| `/block show [name...]` (`s`) | Toggle red outlines of all barriers, or only the named ones. |
| `/block pick` (`p`) / `/block info` (`i`) / `/block why` | Name of the barrier you right-click / look at, and what it does in that cell. |
| `/block off [name] [seconds]` / `/block on [name]` | Switch a barrier off for a while (default `disable-seconds`, `0` = until `on`), or back on. |
| `/block reload` (`rl`) | Re-read `data.yml` and the config. |
| `/block debug`, `/block shulkers` (`sh`), `/block perf` | Troubleshooting: explain sword swings, show the invisible shulkers, and print timings. |

| Permission | Default | Purpose |
|---|---|---|
| `blockblocker.admin` | op | Use `/block` |
| `blockblocker.bypass` | nobody, not even ops | Walk through barriers |

## Config

`plugins/BlockBlocker/config.yml` describes every setting in comments. The main ones:

| Key | Default | Meaning |
|---|---|---|
| `client-blocks` | `true` | Send nearby players fake invisible barrier blocks, which removes rubber-banding. |
| `shulker-colliders` | `false` | **Experimental.** Invisible client-side shulkers make cells with small blocks (fences, slabs, grass...) solid while the block stays visible. Needed by the shape, click-through and attack-through features. **Restart** to change. |
| `respect-partial-blocks` | `true` | Without shulker colliders, cells with a fence or slab use that block's own collision. |
| `thin-blocks` | `[BARS, GLASS_PANE, FENCE, WALL]` | Blocks that keep their thin collision inside a barrier, with the shape copied into the air above and below. |
| `connect-shapes` | `true` | Copied thin shapes join their neighbours, so a row of bars becomes one continuous wall. |
| `shape-copy-partial-blocks` | `true` | Follow-shape barriers also copy doors, trapdoors, slabs and stairs. |
| `max-shape-colliders` | `2500` | Most tiny shulkers sent to one player (nearest first). Beyond that the server enforces the wall. |
| `shape-collider-gap` | `2` | Largest gap between those shulkers, in 1/16 block (0–8). Smaller is smoother but uses more shulkers. |
| `shape-fake-blocks` | `false` | Use invisible fake blocks from `resourcepack/` instead of tiny shulkers. Needs that resource pack. |
| `shape-mode` | `block` | `block`: the copied shape stops players. `push`: it gently pushes them away (`push-strength`, default `0.5`). |
| `block-fill-cell`, `push-fill-cell` | `false` | Make the whole cell above bars solid (or pushing), with no gaps. |
| `mobs-blocked` | `true` | Mobs are stopped by barriers and steered around them. |
| `mob-full-blocks` | `[BARS, FENCE, _WALL, PANE]` | Blocks that count as full blocks for mobs, so zombies don't get stuck in thin gaps. |
| `no-route-planning-worlds` | `[mz_instance_]` | Worlds where another plugin (MinecraftZombies) plans mob routes itself. |
| `mz-barriers` | `false` | Give MinecraftZombies' zombie windows the same smooth client-side collision. |
| `attack-through`, `attack-through-full-blocks` | `true` | Sword swings that hit the invisible wall reach the creature behind it. |
| `click-through` | `true` | Right-clicks on the invisible wall reach the real block behind it, such as a gun sign. |
| `grim-ignore-near-shulkers` | `true` | Ignore GrimAC movement flags right next to the invisible shulkers. |
| `pick-item` | `BONE` | Right-click a barrier with this item to pick it. |
| `disable-seconds` | `60` | How long `/block off` lasts. |

Barriers are saved in `plugins/BlockBlocker/data.yml`.

## Limitations

- Players riding boats, horses or minecarts are not stopped yet.
- A barrier one block tall can be jumped over, like any one-block wall.
- With shulker colliders on, the crosshair can target the invisible shulker instead of the block behind it.
  `click-through` passes plugin clicks on (for example sign shops), but vanilla actions such as opening a chest are
  not. As a fallback, `/block off` switches a barrier off for a while, for everyone in that world.
- Free-form shapes store every block inside them. Beyond 8 million blocks of bounding box, the bounding box is used.

## For plugin developers

Barriers are not blocks, so WorldEdit/FAWE copies don't include them. To copy a world's barriers into a temporary
world (for example an arena copy at the same coordinates), call this on the main thread:

```java
Plugin bb = Bukkit.getPluginManager().getPlugin("BlockBlocker");
if (bb != null && bb.isEnabled()) {
    bb.getClass().getMethod("copyBarriers", String.class, String.class)
      .invoke(bb, templateWorld.getName(), instanceWorld.getName());
}
```

The copies are temporary: they are never saved and are dropped when the target world unloads. Add `BlockBlocker`
to your `softdepend` so it loads first.

## Building

```
./gradlew build        # gradlew.bat build on Windows
```

Requires JDK 25 (Gradle can download one). The jar, with PacketEvents included, is written to `build/libs/`.

## Related projects

- [MZ-Minecraft-Zombies](https://github.com/TTG24/MZ-Minecraft-Zombies): the MinecraftZombies server plugin (round-based zombie arenas).
- [MZ-Movement](https://github.com/TTG24/MZ-Movement): the Fabric client mod (tactical movement, zombie animations, crawlers, gore).

## License and credits

BlockBlocker's source code is released under the [MIT License](LICENSE). The built jar bundles
[PacketEvents](https://github.com/retrooper/packetevents) (GPL-3.0) and [Adventure](https://github.com/KyoriPowered/adventure);
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing a built jar.

Author: [TTG24](https://github.com/TTG24)
