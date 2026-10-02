English documentation. [Русская версия](README_RU.md).

# ItemCounters

Counters for tools, weapons and armor. Track broken blocks, mob and player kills, and damage taken. Includes leaderboards, milestone rewards and PlaceholderAPI support.

**Requirements:** Minecraft 1.21.10, Paper/Purpur, Java 21. PlaceholderAPI is optional.

## Installation and use

Put the plugin JAR in `plugins/` and start the server. Settings are created in `plugins/ItemCounters/config.yml`, with translations in `plugins/ItemCounters/lang/`.

Give a counter with `/counter give <player>`. In an anvil, place a supported item **on the left** and the issued counter **on the right**, then take the result. The default counter is a compass. Applying it costs no XP by default. A regular compass or an item with a copied name will not work. One counter is consumed. An item cannot receive a counter twice.

## What is counted

| Item | Statistic |
| --- | --- |
| Pickaxes, shovels, hoes | All broken blocks |
| Axes | All broken blocks, including stone |
| Swords, maces, bows, crossbows, tridents | Mob and player kills tracked separately |
| Helmets, chestplates, leggings, boots | Damage taken in hearts: 1 ❤ = 2 HP |

Only items with a counter are tracked. Damage after reductions and absorption is split between equipped armor pieces with counters. The player's leaderboard counts it once. Arrows and tridents remember the weapon used to fire them. Switching items does not transfer a kill to another item.

Cancelled actions do not count. Counting in Creative is disabled by default. Spectator never counts. Elytra, shears and wolf armor are unsupported. Existing names, enchantments and lore from other plugins are preserved. Counters stay with the item when it changes hands. They do not protect items from dropping on death.

## Commands and permissions

Arguments in `<...>` are required. Arguments in `[...]` are optional. Item commands use the selected online player's main hand. Without a player name, they use your hand. Console commands require a player name.

| Command | Action |
| --- | --- |
| `/counter help` | Show help |
| `/counter give <player>` | Give one counter |
| `/counter apply [player]` | Apply a counter without an anvil |
| `/counter remove [player]` | Remove a counter from an item |
| `/counter inspect [player]` | View item statistics |
| `/counter set <stat> <number> [player]` | Set a value |
| `/counter add <stat> <number> [player]` | Increase a value |
| `/counter subtract <stat> <number> [player]` | Decrease a value |
| `/counter top <category> <daily\|weekly\|alltime> [page]` | View a leaderboard |
| `/counter reload` | Reload settings and translations |

Stats: `blocks`, `wood`, `mob_kills`, `player_kills`, `damage`. Use a stat supported by the item. `value` is an alias for `blocks/wood/damage`. `total_kills` is calculated automatically. Blocks and kills use whole numbers. Damage can use decimals. Values cannot fall below zero. Administrative changes do not increase the player's leaderboard score.

Permissions `itemcounters.use` (help) and `itemcounters.top` (leaderboards) are granted to everyone by default. Admin permissions: `itemcounters.admin.give`, `.apply`, `.remove`, `.inspect`, `.reload`, `.edit`. The abbreviated names all start with `itemcounters.admin`. The `.edit` permission allows set/add/subtract. All admin permissions default to OP. `itemcounters.admin` grants the full set.

## Leaderboards

Categories: `pickaxe`, `shovel`, `hoe`, `axe`, `sword`, `mace`, `bow`, `crossbow`, `trident`, `armor`, `mixed`. Aggregate categories `blocks`, `wood`, `kills` and `damage` are also available.

Periods: `daily` (day), `weekly` (week), `alltime` (all time). The `mixed` leaderboard lists each player once using their highest numerical result across tool categories. Different counters are not added together. Example: `/counter top mixed alltime`.

Scores belong to players. Trading an item does not transfer a leaderboard position. Daily and weekly periods follow the server timezone or the timezone chosen in the settings.

## PlaceholderAPI: held item

Item placeholders read the player's main hand. The expansion is built into the plugin. No eCloud download is needed.

| Placeholder | Result |
| --- | --- |
| `%counter_has%` | Whether the item has a counter: true/false |
| `%counter_type%` | blocks, wood, weapon or armor |
| `%counter_value%` | Formatted primary value |
| `%counter_raw_value%` | Unformatted primary value |
| `%counter_mob_kills%` | Mob kills |
| `%counter_player_kills%` | Player kills |
| `%counter_total_kills%` | Total kills |
| `%counter_damage%` | Damage in hearts |
| `%counter_unit%` | Value label with the correct word form |
| `%counter_id%` | Unique item ID |
| `%counter_block_word%`, `%counter_kill_word%` | Word form for “block” / “kill” |
| `%counter_mob_word%`, `%counter_player_word%`, `%counter_heart_word%` | Word form for “mob” / “player” / “heart” |

## Leaderboard placeholders

Leaderboards use PlaceholderAPI and do not depend on the item in your hand. Format: `%counter_top_<category>_<period>_<position>_<field>%`. Positions start at 1.

Example: Alex is first on the mixed leaderboard with 2 blocks broken using a pickaxe. The plugin language is `en_US` and the formatting is unchanged. These are the literal placeholder results. Replace `mixed`, `alltime` and `1` with the category, period and position you need.

| Placeholder | Meaning | Example result |
| --- | --- | --- |
| `%counter_top_mixed_alltime_1_name%` | Player name | `Alex` |
| `%counter_top_mixed_alltime_1_value%` | Number and action | `2 blocks with a pickaxe` |
| `%counter_top_mixed_alltime_1_raw%` | Unformatted number | `2` |
| `%counter_top_mixed_alltime_1_position%` | Position | `1` |
| `%counter_top_mixed_alltime_1_category%` | Result category | `pickaxe` |
| `%counter_top_mixed_alltime_1_action%` | Action label | `blocks with a pickaxe` |
| `%counter_top_mixed_alltime_1_line%` | Complete line with color codes | `&61. &eAlex &7— &62 blocks with a pickaxe` |

On a hologram, the last line appears as `1. Alex — 2 blocks with a pickaxe` with colors from the language file. Names, positions, numbers and actions depend on the statistics. Text and colors are set in `leaderboard.row`, `leaderboard.value` and `units`.

Categories: `mixed`, `pickaxe`, `shovel`, `hoe`, `axe`, `sword`, `mace`, `bow`, `crossbow`, `trident`, `armor`; aggregate categories: `blocks`, `wood`, `kills`, `damage`. Periods: `daily`, `weekly`, `alltime`.

More examples: `%counter_top_mixed_alltime_1_line%`, `%counter_top_pickaxe_daily_1_value%`, `%counter_top_sword_weekly_1_name%`. For a hologram, use `%counter_top_mixed_alltime_1_line%`, then repeat with positions 2, 3 and so on.

The short form `%counter_top_1_value%` and the other fields use `leaderboards.default-category/default-period`. Missing leaderboard positions return `leaderboards.empty-placeholder` (default: `—`). Item placeholders return `placeholderapi.empty-item` (default: `0`) when there is no counter. `%counter_has%` returns `false`.

## Settings and formatting

| Setting | Options |
| --- | --- |
| `general.language` | ru_RU, en_US or a custom language file |
| `anvil` | enabled, counter-material, counter-glow, level-cost (0 to 39 levels) |
| `counting.creative` | Counting in Creative |
| `supported-items` | Enable blocks, wood, kills, damage |
| `entity-exclusions` | Excluded entity types and regular expressions in patterns |
| `number-format` | Thousands and decimal separators, damage-decimals |
| `leaderboards` | Timezone, week start, default category/period, cache/page sizes, refresh interval and retention of old periods |
| `storage` | SQLite/MySQL, file name, flush-seconds, legacy-import and MySQL connection settings |
| `milestones` | Rewards for thresholds reached on individual items |
| `placeholderapi` | enabled and empty-item response |

`counter-glow: false` is the default. The setting affects newly issued counters. Glowing does not add enchantments. Numbers are rounded for display only.

Language files contain `messages`, `usage`, `help`, `counter-item` (the issued item), `item-lore`, `inspect`, `units`, `categories`, `periods`, `leaderboard` and `forms`. Colors use `&` codes. Italics are off by default; `&o` enables them.

Lore/inspection variables: `{value}`, `{raw_value}`, `{mob_kills}`, `{player_kills}`, `{total_kills}`, `{damage}`, `{unit}`, `{type}`, `{id}`, `{block_word}`, `{kill_word}`, `{mob_word}`, `{player_word}`, `{heart_word}`. Inspection also supports `{player}`.

`forms.rule` selects russian/english. The `block/kill/mob/player/heart` dictionaries contain `one/few/many/other`. For Russian, this gives “1 блок”, “2 блока”, “5 блоков”. Decimal values, including 1.0, use other. The Russian template `units.pickaxe: '{block_word} киркой'` selects the word form automatically; its English equivalent is `'{block_word} with a pickaxe'`. Custom dictionaries can be used as `{name_word}` and `%counter_name_word%`.

Leaderboard template variables: `header` accepts `{category}`, `{period}`, `{page}`; `row` accepts `{position}`, `{player}`, `{value}`; `value` accepts `{value}`, `{unit}`. Command hints support `{usage}`. Replies use variables for the corresponding action.

Run `/counter reload` after editing. Storage connections, the save interval, timezone/week start, leaderboard refresh interval and PAPI registration require a restart. Settings and custom translations are preserved when files are updated. An invalid reload keeps the previous settings.

## Rewards and storage

Rewards are disabled by default. Example: one diamond for 100 kills on an item.

```yaml
milestones:
  trigger-on-admin-change: false
  kills:
    '100':
      commands:
        - 'console:minecraft:give {player} diamond 1'
```

Reward categories: blocks, wood, kills, damage. Use `'100_5'` for a decimal threshold of 100.5. Each threshold triggers once per item. `console:` runs the command as console. `player:` runs it with the player's permissions. Do not include a leading slash. Variables: `{player}`, `{uuid}`, `{item}`, `{threshold}` and the item variables listed above. `trigger-on-admin-change` enables rewards for administrative value changes.

Player statistics use SQLite (`counters.db`) by default. For MySQL, set `storage.type: mysql` and the connection in `storage.mysql`, then restart the server. Changing the storage type does not migrate data. `legacy-import` imports an old JSON file once into an empty database, preserving the original file and a backup. Before updates, back up worlds, player data and the database together. An abrupt shutdown can lose the latest unsaved actions.

A Java API is available for integration with other plugins: read, apply, edit and remove counters. API changes do not increase leaderboard scores.

## License

You can install the plugin on your own servers, including commercial servers, and modify it for personal use free of charge. Reselling and redistributing copies require written permission from der3kyy. Full terms and library licenses are in [LICENSE](LICENSE).
