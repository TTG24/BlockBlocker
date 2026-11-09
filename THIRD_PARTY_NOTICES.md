# Third-party notices

## PacketEvents

- Project: https://github.com/retrooper/packetevents
- Authors: retrooper and contributors
- License: **GPL-3.0** (https://www.gnu.org/licenses/gpl-3.0.html)
- Used for: the optional client-side shulker colliders (`shulker-colliders` in the config)

The BlockBlocker **source code in this repository** is MIT-licensed and does not contain PacketEvents. PacketEvents is
pulled in at build time as a Gradle dependency (`com.github.retrooper:packetevents-spigot`).

The **built plugin jar** bundles PacketEvents (relocated into `dev.blockblocker.libs.packetevents`). Because PacketEvents is
GPL-3.0, redistributing that built jar is subject to PacketEvents' GPL-3.0 terms (for example, passing on the license and
making the corresponding source available; this repository is that source). If you need a binary that is not subject to
those terms, do not redistribute a jar that bundles PacketEvents.

This is a plain description, not legal advice.

## Adventure and examination

- Project: https://github.com/KyoriPowered/adventure
- Authors: KyoriPowered
- License: MIT

Transitive dependencies of PacketEvents. They are bundled in the built jar, although Paper's own copy is used at runtime when
the server provides one.
