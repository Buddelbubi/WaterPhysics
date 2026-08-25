# WaterPhysics

Terraria-like water physics for PowerNukkitX.

Water is finite and can move between blocks. It falls down, spreads sideways and
settles into an even surface. Unlike normal Minecraft water, source blocks do not
create unlimited new water.

## Features

- Finite, volume-based water
- Natural downward and sideways flow
- Smooth water surfaces without long stair-like shapes
- Waterlogged block support
- Works in selected worlds or all worlds
- Adjustable speed and performance settings
- Only simulates water near players by default

## Requirements

- A PowerNukkitX server with API 3.0.0
- Java 21 or newer

## Installation

1. Download or build `WaterPhysics.jar`.
2. Place it in your server's `plugins` folder.
3. Start or restart the server.
4. Edit `plugins/WaterPhysics/config.yml` if needed.
5. Run `/wp reload` after changing the configuration.

## Configuration

```yaml
enabled: true

# Use "*" for every world, or enter individual world names.
worlds:
  - "*"

flow:
  batch-size: 512
  tick-interval: 3
  level-lookahead: 16

optimization:
  player-proximity-check: true
  player-proximity-chunks: 4
```

### Simple performance guide

- Lower `batch-size` if the server starts lagging.
- Increase `tick-interval` to reduce server load.
- Lower `level-lookahead` if large lakes use too much processing time.
- Keep `player-proximity-check` enabled on large servers.
- Increase `batch-size` if water moves too slowly and the server has enough performance.

The default settings are a good starting point for most servers.

## Commands

| Command | Description |
| --- | --- |
| `/wp status` | Shows whether the plugin is running and the current queue size. |
| `/wp reload` | Reloads the configuration. |
| `/wp enable` | Enables water physics. |
| `/wp disable` | Disables water physics. |
| `/wp stop` | Same as `/wp disable`. |

The commands also work with `/water` and `/waterphysics`.

Permission: `waterphysics.admin`

## AI Note

This plugin is fully made ai written and optimized by human intervention.
It works fine, but code quality might not be that good.
