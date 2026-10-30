package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.economycore.engine.CreditAccount;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Blackjack against the house, at a table of up to seven.
 * <p>
 * A record for the same reason roulette is one: a game is configuration and
 * a factory. The hand in progress lives in {@link BlackjackSession}, and the
 * shoe lives with the table, since it outlasts every hand dealt from it.
 * <p>
 * Three rules are the house's to choose — how many decks, whether the dealer
 * hits a soft seventeen, and what a blackjack pays — alongside the wager
 * limits and the buy-in every banked table posts. The rest is fixed; see
 * {@link BlackjackRules}.
 *
 * @param id registry id, persisted in table block entities
 */
public record BlackjackGame(String id) implements Game {

    /** Seven spots, as on the felt of every blackjack table. */
    private static final int MAX_SEATS = 7;

    private static final long DEFAULT_MINIMUM_BET = 10;

    /** Ten minimum wagers, as at roulette: a few hands' worth, not a wall. */
    private static final long DEFAULT_BUY_IN_MINIMUM = 100;

    /**
     * The shoe sizes a table may be set to, in the order offered. Appended to,
     * never rearranged: a table stores its choice as a position here.
     */
    private static final List<Integer> DECK_COUNTS = List.of(1, 2, 4, 6, 8);

    public BlackjackGame {
        Objects.requireNonNull(id, "id");
    }

    public static BlackjackGame standard() {
        return new BlackjackGame("blackjack");
    }

    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public int maxPlayers() {
        return MAX_SEATS;
    }

    @Override
    public boolean usesBetting() {
        return true;
    }

    @Override
    public boolean isHouseBanked() {
        return true;
    }

    @Override
    public long minimumBet() {
        return DEFAULT_MINIMUM_BET;
    }

    // --- Configuration -------------------------------------------------------------------

    public SettingSpec.Amount minimumWager() {
        return new SettingSpec.Amount(id + ".bet_min", DEFAULT_MINIMUM_BET, 1, CreditAccount.MAX_BALANCE);
    }

    /** Zero posts no ceiling of the table's own; the bankroll's still binds. */
    public SettingSpec.Amount maximumWager() {
        return new SettingSpec.Amount(id + ".bet_max", BlackjackLimits.UNLIMITED,
                BlackjackLimits.UNLIMITED, CreditAccount.MAX_BALANCE);
    }

    public SettingSpec.Choice decks() {
        return new SettingSpec.Choice(id + ".decks",
                DECK_COUNTS.stream().map(String::valueOf).toList(),
                DECK_COUNTS.indexOf(BlackjackRules.STANDARD.decks()));
    }

    public SettingSpec.Flag dealerHitsSoft17() {
        return new SettingSpec.Flag(id + ".hits_soft_17", BlackjackRules.STANDARD.dealerHitsSoft17());
    }

    public SettingSpec.Choice naturalPayout() {
        return new SettingSpec.Choice(id + ".natural_pays",
                Arrays.stream(NaturalPayout.values()).map(NaturalPayout::optionName).toList(),
                BlackjackRules.STANDARD.naturalPayout().ordinal());
    }

    public SettingSpec.Amount buyInMinimum() {
        return new SettingSpec.Amount(id + ".buy_in_min", DEFAULT_BUY_IN_MINIMUM, 1, CreditAccount.MAX_BALANCE);
    }

    public SettingSpec.Amount buyInMaximum() {
        return new SettingSpec.Amount(
                id + ".buy_in_max", BuyIn.UNLIMITED, BuyIn.UNLIMITED, CreditAccount.MAX_BALANCE);
    }

    /** The wager limits first, then the house's rules, then the buy-in. */
    @Override
    public List<SettingSpec> settings() {
        return List.of(minimumWager(), maximumWager(),
                decks(), dealerHitsSoft17(), naturalPayout(),
                buyInMinimum(), buyInMaximum());
    }

    @Override
    public Optional<BuyIn> buyIn(TableSettings settings) {
        return Optional.of(new BuyIn(settings.get(buyInMinimum()), settings.get(buyInMaximum())));
    }

    @Override
    public Optional<String> settingsProblem(TableSettings settings) {
        long minimum = settings.get(minimumWager());
        long maximum = settings.get(maximumWager());
        if (maximum != BlackjackLimits.UNLIMITED && maximum < minimum) {
            return Optional.of("tablegames.setting.problem.bet_inverted");
        }
        long buyInMaximum = settings.get(buyInMaximum());
        if (buyInMaximum != BuyIn.UNLIMITED && buyInMaximum < settings.get(buyInMinimum())) {
            return Optional.of("tablegames.setting.problem.buy_in_inverted");
        }
        // A stack that cannot cover the smallest wager seats somebody who can
        // only watch.
        if (settings.get(buyInMinimum()) < minimum) {
            return Optional.of("tablegames.setting.problem.buy_in_below_bet");
        }
        return Optional.empty();
    }

    /** The opening wagers a table set up this way takes. Callers clear {@link #settingsProblem} first. */
    public BlackjackLimits limitsFrom(TableSettings settings) {
        return new BlackjackLimits(settings.get(minimumWager()), settings.get(maximumWager()));
    }

    /** The rules a table set up this way plays. */
    public BlackjackRules rulesFrom(TableSettings settings) {
        return new BlackjackRules(
                DECK_COUNTS.get((int) settings.get(decks())),
                dealerHitsSoft17().isOn(settings.get(dealerHitsSoft17())),
                NaturalPayout.values()[(int) settings.get(naturalPayout())]);
    }

    // --- Sessions ------------------------------------------------------------------------

    /**
     * A hand at the standard rules from a fresh shoe, every seat wagering the
     * default minimum. Satisfies the interface; a table uses the overload,
     * which knows its shoe, its rules and what each seat actually put down.
     */
    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        Map<UUID, Long> bets = new LinkedHashMap<>();
        for (Seat seat : seats) {
            bets.put(seat.playerId(), DEFAULT_MINIMUM_BET);
        }
        return createSession(seats, random, BlackjackRules.STANDARD,
                new Shoe(BlackjackRules.STANDARD.decks(), random), bets);
    }

    public BlackjackSession createSession(List<Seat> seats, RandomGenerator random,
                                          BlackjackRules rules, Shoe shoe,
                                          Map<UUID, Long> openingBets) {
        if (!canStartWith(seats.size())) {
            throw new IllegalArgumentException(
                    "Blackjack needs " + minPlayers() + " to " + maxPlayers()
                            + " players, got " + seats.size());
        }
        return new BlackjackSession(seats, random, rules, shoe, openingBets);
    }
}
