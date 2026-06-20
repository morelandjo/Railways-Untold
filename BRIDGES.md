# Bridges

Railways Untold builds bridges automatically wherever the track needs them, and the whole look of a
bridge is driven by a small set of **structure NBT files** that you can edit and swap. This page
explains when bridges appear, the pieces that make one up, and exactly how to restyle them.

---

## When does a bridge appear?

The track generator decides per-block, as it lays track, whether that spot should be a bridge. It bridges when **either**:

- **Elevated** — the deck is more than `bridgeElevationThreshold` blocks (default **4**) above the ground below, **or**
- **Over water** — a significant share of the area just below the track is water.

Otherwise it fills the gap with terrain instead. Short stretches that dip between two bridge sections are carried across so you don't get holes, and a short run over a genuinely deep gap (e.g. a brief straight between two diagonal spans over a lake) is always bridged rather than left open.

You don't place bridges manually — you style them by editing the pieces below.

---

## The pieces

A bridge is assembled from three pieces, each its own NBT file, plus an optional **diagonal variant** of each for 45° sections:

| Piece | File | What it is |
|---|---|---|
| **Deck** | `bridge_cross_section.nbt` | The walking/track surface and anything under it (fascia, girders). |
| **Railing** | `bridge_railing.nbt` | The repeating side railing along both deck edges. |
| **Pier** | `bridge_pillar.nbt` | The support column(s) that drop to the ground, plus deck-level and above-deck decoration. |
| Deck (diagonal) | `bridge_cross_section_diag.nbt` | Deck used on 45° sections. |
| Railing (diagonal) | `bridge_railing_diag.nbt` | Railing used on 45° sections. |
| Pier (diagonal) | `bridge_pillar_diag.nbt` | Pier used on 45° sections. |

> The diagonal (`_diag`) variants are **optional**. If a piece has no `_diag` file, the normal piece is used on diagonals (it will look rougher because square blocks can't follow a 45° line cleanly).

All three pieces — deck, railing, and pier — are chosen **per biome** (see [Bridge settings](#bridge-settings)), so different biomes can have completely different bridges.

---

## Where the files live & how to edit them

- **In the mod:** `data/railwaysuntold/structure/<name>.nbt`
- **Ready-to-edit copies** are shipped in the `schematics/` folder of your Minecraft instance so you can import them straight into [Create](https://www.curseforge.com/minecraft/mc-mods/create).

**To restyle a piece:**

1. Open the `.nbt` in Create — place a **Schematic Table**, load the matching file from `schematics/`, and deploy it with a Schematicannon (or just open it in a structure block).
2. Edit the blocks however you like, keeping the **layout rules** below for that piece.
3. Save/export it under your own pack at `data/<namespace>/structure/<name>.nbt`, then point a biome at it (or overwrite the mod's file in place to change the default everywhere).
4. Relaunch and generate fresh chunks to see the change.

Every piece is just a normal Minecraft structure, so any tool that reads/writes `.nbt` structures works.

---

## 1. The deck — `bridge_cross_section.nbt`

The deck is authored as a **cross-section: a single slice looking *across* the track.**

```
X  = across the track (the deck width)      ← build left-to-right here
Y  = up
Z  = 1 block deep (just one slice)
```

**How the width is filled.** The slice's **two outer columns** (left edge, right edge) are placed exactly as drawn at the deck edges. The **centre column is repeated** across the whole interior to fill the deck to its full width. So a 3-wide slice (`edge · centre · edge`) becomes a deck of whatever width you've configured.

**Where the deck surface is.** The mod finds the **topmost solid block of the centre column** and treats that as the deck/track level. Anything you put **below** it in the slice is stamped **under the deck** (fascia, girders, beams); the surface block itself is what the track rides on.

**Example** (the shipped deck, drawn across the track, top row first):

```
        left edge   centre   right edge
 y1:    oak_log     stone     oak_log     ← deck surface
 y0:      –         dark_log     –        ← fascia, hangs under the middle
```

That produces a stone walkway with log edges and a dark-log fascia strip running under the centre of the deck.

**Rules / tips**

- Width should be **odd** (so there's a clear centre column). The physical deck width comes from `bridge_half_width` (below), *not* the slice width — the slice just needs `edge · centre · edge`.
- Use blocks that look the same from any direction (full blocks, vertical logs) where possible — deck blocks are rotated to face the track, so a sideways-facing block can end up turned.
- Put fascia/under-deck detail **below** the centre's surface block. Put it in the **centre** column to run it down the whole interior, or in the **edge** columns to run it under just the edges.

---

## 2. The railing — `bridge_railing.nbt`

The railing is authored as a **pattern that runs *along* the track and repeats.**

```
X  = along the track (the repeating pattern)   ← build the pattern here, left-to-right
Y  = up (railing height)
Z  = 1 block wide
```

The pattern is stamped on **both deck edges**, just above the deck surface, repeating every `X` blocks as the track advances. So a 6-long pattern repeats every 6 blocks.

- Directional blocks (e.g. **fence gates**) are rotated to line up with the track direction automatically.
- The railing runs **continuously**; where a pier rises through it, the pier's own post simply takes over that block.

**Example** (the shipped railing is 6 long): `gate · fence · gate · gate · fence · gate`, repeating.

**Rules / tips**

- Keep it **1 block wide** (Z = 1). Height can be whatever you like (1–2 looks best).
- The pattern length is purely cosmetic spacing for the railing itself — **pier spacing is set separately** (see piers).
- The railing is chosen **per biome** with `bridge_railing_nbt` (below), just like the deck and pier — so each biome can carry its own rail. Leave the key off and the biome uses the default `bridge_railing`.

---

## 3. The piers — `bridge_pillar.nbt`

The pier is the most capable piece: a small **3D model** stamped at each support point and rotated to the track.

```
X  = along the track (pier "depth" / thickness)
Y  = up
Z  = across the track (where the legs sit relative to the deck)
```

**Vertical layout is driven by one number — `bridge_pillar_deck_row`** (a datapack setting, below). That number says *which Y row of your model sits at the deck surface*. Everything is placed relative to it:

```
rows ABOVE the deck row   → above the deck   (post tops, lamps, parapet)
the deck row              → at deck level     (where it meets the deck)
rows BELOW the deck row   → under the deck    (brackets, braces)
the bottom row (y = 0)    → the FOOT          → extends straight down to the ground
```

So you build the pier bottom-to-top: legs at the bottom, under-deck brackets above them, the deck row where it meets the bridge, and any decoration on top — then set `bridge_pillar_deck_row` to the row where the deck is.

**Legs snap to the deck edges.** The mod looks at your **bottom row (y = 0)** to find the legs, and spreads them so the outermost legs land exactly on the deck edges, regardless of the configured deck width. Anything you draw **outside** the legs (e.g. trapdoor trim) hangs just past the edge as overhang decoration.

**Pier spacing** = the **railing pattern length + the pier's along-track depth (its X size)**. So a 6-long railing with a 1-deep pier puts a pier every 7 blocks; a 5-deep pier spaces them further apart. Make the pier deeper or the railing longer to spread piers out.

**Rules / tips**

- Put your **leg/foot material in the bottom row (y = 0)** — that's the part that runs to the ground, and it's what defines the edge spread.
- Set `bridge_pillar_deck_row` to match where the deck meets your model. For a pier with two rows of brackets under the deck and the deck at the third row, that's `3`.
- The pier is rotated to the track, so directional blocks (stairs, trapdoors) will turn — check them on a few different track directions.

---

## Diagonal & curve variants (`_diag`)

Minecraft can only place blocks on a square grid, so a 45° run (and the diagonal portions of any curve) needs pieces drawn for the diagonal. Provide a `_diag` version of any piece and the mod uses it automatically on those sections:

- `bridge_cross_section_diag.nbt`, `bridge_railing_diag.nbt`, `bridge_pillar_diag.nbt`.
- A **curve is treated as a sequence of straight + diagonal steps**, so the deck/railing/piers switch to the `_diag` pieces along the diagonal parts of a curve and back on the straight parts.
- These are **optional** — leave them out and the normal pieces are used on diagonals (rougher, but functional).

**Authoring the diagonal pieces** (advanced):

- Build them **rotated 45°** — i.e. lay the deck/legs along the diagonal of the X–Z square instead of straight along an axis.
- For the **diagonal deck**, draw the across-track profile along one diagonal of the slice (an `edge · centre · edge` line at 45°); it's still expanded to full width with the centre duplicated, same as the straight deck.
- Prefer **direction-neutral blocks** (full blocks, vertical logs, stone) — they read correctly on all four diagonals without fuss.
- The diagonal pieces are the newest part of the system; expect to tweak them by eye across the four diagonal directions.

---

## Bridge settings

### Datapack (per-biome) — `data/railwaysuntold/railwaysuntold/biome_settings/defaults.json`

These choose which NBTs to use and the deck dimensions. You can override any of them per-biome with additional biome-settings JSON files.

| Key | Meaning | Default |
|---|---|---|
| `bridge_decking_nbt` | The deck piece id. | `railwaysuntold:bridge_cross_section` |
| `bridge_pier_nbt` | **The pier piece id.** | `railwaysuntold:bridge_pillar` |
| `bridge_railing_nbt` | The railing piece id. | `railwaysuntold:bridge_railing` |
| `bridge_half_width` | Half the deck width. `3` → a 7-block-wide deck (`2 × 3 + 1`). | `3` |
| `bridge_pillar_deck_row` | Which Y row of the pier model sits at the deck surface (see Piers). | `3` |

The `_diag` variants don't need their own keys — the mod looks for `<id>_diag` automatically.

### Mod config

| Option | Meaning | Default |
|---|---|---|
| `bridgeElevationThreshold` | How many blocks above ground the track must be before it bridges (over land). `0` = only bridge over water. | `4` |

---

## Making your own bridge style — quick recipe

1. **Deck:** edit `bridge_cross_section.nbt` — draw `edge · centre · edge` across the track, put fascia below the centre. (Optionally set `bridge_half_width` for a wider/narrower deck.)
2. **Railing:** edit `bridge_railing.nbt` — draw your repeating side pattern along the track, 1 wide. Point a biome at it with `bridge_railing_nbt` (or overwrite the default file).
3. **Pier:** edit `bridge_pillar.nbt` — legs in the bottom row, brackets/deck/decoration stacked up, then set `bridge_pillar_deck_row` to the deck row. Tune pier spacing via the railing length or pier depth.
4. **(Optional) Diagonals:** add `_diag` versions of any piece you want to look clean on 45° track.
5. Point a biome at your pieces in `biome_settings` (or just overwrite the default files), relaunch, explore fresh chunks.

---

## Tips & gotchas

- **Always regenerate fresh chunks** after a change — existing bridges are baked into already-generated terrain.
- **Deck blocks and pier blocks are rotated to the track.** Full/symmetric blocks are safest; check stairs, gates, trapdoors, and logs across several track directions.
- **The foot (pier bottom row) goes to the ground.** Over deep water or ravines you'll see the foot drop a long way — that's expected; keep under-deck brackets in the rows just below the deck row so they hug the deck rather than the floor.
- **Pier too narrow / posts not at the edges?** The legs are read from the bottom row — make sure your leg/foot columns are in `y = 0`.
- There are **no abutments/headwalls** — bridges meet the bank directly with the deck and piers.
</content>
