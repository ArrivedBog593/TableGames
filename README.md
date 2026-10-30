# TableGames

A [NeoForge](https://neoforged.net/) 1.21.1 mod that adds casino-style table games to
Minecraft, played with the credits of [EconomyCore](../EconomyCore) — accounts, an
exchange that turns any item into credits, and a shop.

Roulette (European and American) and slot machines are playable today. Blackjack, poker, and a
handful of non-betting games (Uno, dominó, rummy) are planned — see the architecture
notes below for what already supports adding them.

## Highlights

- **Audited settlement**: every hand is checked against real, live balances and the
  house bankroll before a single credit moves, and applied all at once — a hand never
  ends with half a payout distributed.
- **A bankroll that actually protects itself**: table maximums are derived from the
  house balance, not configured by hand, and shrink automatically if the house is
  losing. The bank is an account in EconomyCore's ledger, and receives the
  server's income — what the shop sells, what the exchange's surcharge earns. `HouseExposure` and `PlayerCommitments` track what every table and every
  player has riding on rounds that haven't settled, so nobody can double-commit the
  same credits across two tables.
- **A player-owned table**: whoever places a table configures it, and can share that
  right with specific guests, without needing an operator.

## Requirements

- Minecraft 1.21.1
- [NeoForge](https://neoforged.net/) 21.1.248+
- [EconomyCore](../EconomyCore) 0.1.0+
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

EconomyCore comes from Maven local during development: build it once with
`./gradlew publishToMavenLocal` in its own project, and again after changing it.

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
/tablegames help                  # the casino's commands; /economycore help for the economy's
/tablegames house add 1000000     # tables won't open with an empty bankroll
/give @s tablegames:table
/tablegames table set roulette    # while looking at the table
/economycore staff add <player> admin   # staff who can run the house and every table
```

Vanilla's spawn protection blocks non-operators from using any block near spawn —
build away from it, or set `spawn-protection=0` in `server.properties`.

The house's risk settings live in `<world>/data/tablegames_house.dat`. Balances, prices
and the transaction log are EconomyCore's.

## License

MIT — see [LICENSE](LICENSE).
