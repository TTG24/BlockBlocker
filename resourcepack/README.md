# BlockBlocker resource pack

Makes three blocks draw as nothing, so BlockBlocker can send them to players as invisible, real-collision stand-ins for the
thin shapes of iron bars/panes, fences and walls (config `shape-fake-blocks: true`). **Never use these blocks in your maps:**
they become invisible for everyone with the pack.

- `magenta_stained_glass_pane` (stand-in for iron bars and glass panes)
- `cherry_fence` (stand-in for fences)
- `resin_brick_wall` (stand-in for walls)

Merge `assets/` into your existing server resource pack (or use this pack on its own). To use other blocks, change `fake-pane`,
`fake-fence` and `fake-wall` in BlockBlocker's `config.yml` and rename the three files in `assets/minecraft/blockstates/` to match.
