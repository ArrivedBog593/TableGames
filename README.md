# TableGames

A [NeoForge](https://neoforged.net/) 1.21.1 mod that adds casino-style table games to
Minecraft, backed by a configurable item-to-credit economy that works with any mod's
items as currency.

Roulette (European and American) is playable today. Blackjack, poker, slots, and a
handful of non-betting games (Uno, dominó, rummy) are planned — see the architecture
notes below for what already supports adding them.

## Highlights

- **Item ↔ credit economy**, configurable in-game: any item id converts to credits at a
  value an operator sets, with an optional resale spread that funds the house bankroll.
  No dependency on any specific currency mod — items are addressed by id as plain text.
- **Anti-exploit validation**: proposed conversion values are checked against real
  crafting recipes before they're accepted, so a set of prices that would let players
  mint credits by crafting is refused, not silently exploited.
- **Audited settlement**: every hand is checked against real, live balances and the
  house bankroll before a single credit moves, and applied all at once — a hand never
  ends with half a payout distributed.
- **A bankroll that actually protects itself**: table maximums are derived from the
  house balance, not configured by hand, and shrink automatically if the house is
  losing. `HouseExposure` and `PlayerCommitments` track what every table and every
  player has riding on rounds that haven't settled, so nobody can double-commit the
  same credits across two tables.
- **A player-owned table**: whoever places a table configures it, and can share that
  right with specific guests, without needing an operator.
- **Crash-safe accounting**: a append-only transaction log lets balances recover after
  a crash between two saves, verified by actually killing the process mid-run.

## Requirements

- Minecraft 1.21.1
- [NeoForge](https://neoforged.net/) 21.1.238+
- Java 21

## Architecture

```
engine/     Java, zero Minecraft dependencies, covered by JUnit 5
platform/   NeoForge: blocks, menus, networking, persistence, commands
client/     Client-only screens, gated behind Dist.CLIENT
```

`engine/` never imports `net.minecraft` or `net.neoforged`. That's what keeps its test
suite fast enough to run on every build and lets game rules be verified without booting
a server. Adding a new game means writing one `Game` and one `GameSession` in `engine/`
— the table block, networking, and settlement machinery in `platform/` are already
written against those two abstractions and don't change.

## Building and running

```
./gradlew build          # compile and run the JUnit suite
./gradlew test           # just the tests — a few hundred, well under a minute
./gradlew runClient      # launch a dev client
./gradlew runClientAlt   # a second client, logged in as a different player
./gradlew runServer      # a dedicated server
```

Wait for the first client to finish loading before launching a second — starting them
together makes Gradle recompile while one is still reading `build/classes`, and the
second fails with `NoClassDefFoundError`. That's a Gradle ordering issue, not a bug in
the mod.

If you change a network payload's shape, recompile both clients before testing them
together. `ModPayloads.VERSION` is bumped whenever a payload changes, and two clients on
different versions correctly refuse to connect to each other rather than misreading a
packet.

## Trying it out

```
/tablegames help                  # the full command directory, paginated and clickable
/tablegames house add 1000000     # tables won't open with an empty bankroll
/give @s tablegames:table
/tablegames table set roulette    # while looking at the table
/tablegames admin give <player>   # hands out an administration card
```

Vanilla's spawn protection blocks non-operators from using any block near spawn —
build away from it, or set `spawn-protection=0` in `server.properties`.

Player balances live in `<world>/data/tablegames_credits.dat`, economy configuration in
`tablegames_economy.dat`, and the recoverable transaction log under `<world>/logs/`.

## License

MIT — see [LICENSE](LICENSE).
