package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.economycore.api.Credits;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Blackjack at a table of up to seven, in one of three {@link BlackjackFormat}s.
 * <p>
 * A record for the same reason roulette is one: a game is configuration and
 * a factory. The hand in progress lives in {@link BlackjackSession}, a
 * tournament's score in {@link BlackjackTournament}, and the shoe with the
 * table, since it outlasts every hand dealt from it.
 * <p>
 * Every format plays the same hand, so the three rules a table may choose —
 * how many decks, whether the dealer hits a soft seventeen, and what a
 * blackjack pays — are offered by all of them. The rest is fixed; see
 * {@link BlackjackRules}. What differs is the money:
 * <ul>
 *   <li>against the house, wager limits and a buy-in, as at roulette;</li>
 *   <li>in a tournament, an entry, the chips it buys, how many hands, and
 *       how the pot is shared;</li>
 *   <li>with a player holding the bank, the one wager everybody plays for,
 *       how the bank draws, who takes a tie, and a buy-in.</li>
 * </ul>
 * The two games between players can be played for fun, with nothing paid.
 *
 * @param id     registry id, persisted in table block entities
 * @param format who the table plays against
 */
public record BlackjackGame(String id, BlackjackFormat format) implements Game {

    /** Seven spots, as on the felt of every blackjack table. */
    private static final int MAX_SEATS = 7;

    private static final long DEFAULT_MINIMUM_BET = 10;

    /** Ten minimum wagers, as at roulette: a few hands' worth, not a wall. */
    private static final long DEFAULT_BUY_IN_MINIMUM = 100;

    private static final long DEFAULT_ENTRY = 500;

    /** Fifty minimum wagers: enough to last fifteen hands with room to press. */
    private static final long DEFAULT_TOURNAMENT_CHIPS = 5000;

    private static final long DEFAULT_TOURNAMENT_MINIMUM = 100;

    private static final long DEFAULT_BANKER_BET = 500;

    /**
     * Ten wagers. Less is legal — the bank only has to cover the hand it is
     * dealt — but a stack that cannot hold the bank for a table of three is
     * one that keeps skipping its turn at it.
     */
    private static final long DEFAULT_BANKER_BUY_IN = 5000;

    /**
     * The shoe sizes a table may be set to, in the order offered. Appended to,
     * never rearranged: a table stores its choice as a position here.
     */
    private static final List<Integer> DECK_COUNTS = List.of(1, 2, 4, 6, 8);

    /** How long a tournament may run, in hands. Appended to, never rearranged. */
    private static final List<Integer> TOURNAMENT_HANDS = List.of(5, 10, 15, 20, 25);

    /** How a player bank draws: by its own hand, or by the dealer's rule. */
    private static final List<String> BANKER_PLAYS = List.of("by_hand", "dealer_rule");

    /** Who takes a tie against a player bank: nobody, or the bank. */
    private static final List<String> TIES = List.of("push", "banker");

    public BlackjackGame {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
    }

    /** Against the house. */
    public static BlackjackGame standard() {
        return new BlackjackGame("blackjack", BlackjackFormat.HOUSE);
    }

    public static BlackjackGame tournament() {
        return new BlackjackGame("blackjack_tournament", BlackjackFormat.TOURNAMENT);
    }

    /** With the bank passing round the players. */
    public static BlackjackGame banker() {
        return new BlackjackGame("blackjack_banker", BlackjackFormat.BANKER);
    }

    /** Every format is blackjack. */
    @Override
    public String family() {
        return "blackjack";
    }

    /** One against the house; two to play against each other. */
    @Override
    public int minPlayers() {
        return format.isHouse() ? 1 : 2;
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
        return format.isHouse();
    }

    @Override
    public long minimumBet() {
        return DEFAULT_MINIMUM_BET;
    }

    // --- Configuration -------------------------------------------------------------------

    public SettingSpec.Amount minimumWager() {
        return new SettingSpec.Amount(id + ".bet_min",
                format == BlackjackFormat.TOURNAMENT ? DEFAULT_TOURNAMENT_MINIMUM : DEFAULT_MINIMUM_BET,
                1, Credits.MAX_BALANCE);
    }

    /** Zero posts no ceiling of the table's own; against the house, the bankroll's still binds. */
    public SettingSpec.Amount maximumWager() {
        return new SettingSpec.Amount(id + ".bet_max", BlackjackLimits.UNLIMITED,
                BlackjackLimits.UNLIMITED, Credits.MAX_BALANCE);
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
        return new SettingSpec.Amount(id + ".buy_in_min",
                format == BlackjackFormat.BANKER ? DEFAULT_BANKER_BUY_IN : DEFAULT_BUY_IN_MINIMUM,
                1, Credits.MAX_BALANCE);
    }

    public SettingSpec.Amount buyInMaximum() {
        return new SettingSpec.Amount(
                id + ".buy_in_max", BuyIn.UNLIMITED, BuyIn.UNLIMITED, Credits.MAX_BALANCE);
    }

    /** Off plays between players for nothing: no entry, no buy-in, nothing settled. */
    public SettingSpec.Flag forMoney() {
        return new SettingSpec.Flag(id + ".for_money", true);
    }

    public SettingSpec.Amount entry() {
        return new SettingSpec.Amount(id + ".entry", DEFAULT_ENTRY, 1, Credits.MAX_BALANCE);
    }

    public SettingSpec.Amount startingChips() {
        return new SettingSpec.Amount(id + ".starting_chips", DEFAULT_TOURNAMENT_CHIPS,
                1, Credits.MAX_BALANCE);
    }

    public SettingSpec.Choice tournamentHands() {
        return new SettingSpec.Choice(id + ".hands",
                TOURNAMENT_HANDS.stream().map(String::valueOf).toList(), TOURNAMENT_HANDS.indexOf(15));
    }

    public SettingSpec.Choice prizeSplit() {
        return new SettingSpec.Choice(id + ".prize",
                Arrays.stream(PrizeSplit.values()).map(PrizeSplit::optionName).toList(), 0);
    }

    /** What every seat plays for, every hand, against a player bank. */
    public SettingSpec.Amount fixedBet() {
        return new SettingSpec.Amount(id + ".bet", DEFAULT_BANKER_BET, 1, Credits.MAX_BALANCE);
    }

    public SettingSpec.Choice bankerPlays() {
        return new SettingSpec.Choice(id + ".banker_plays", BANKER_PLAYS, 0);
    }

    public SettingSpec.Choice ties() {
        return new SettingSpec.Choice(id + ".ties", TIES, 0);
    }

    /**
     * What a table of this format can be set to: the money first, then the
     * house's rules, then the buy-in. A tournament has none: its entry is
     * its buy-in.
     */
    @Override
    public List<SettingSpec> settings() {
        List<SettingSpec> specs = new ArrayList<>();
        switch (format) {
            case HOUSE -> specs.addAll(List.of(minimumWager(), maximumWager()));
            case TOURNAMENT -> specs.addAll(List.of(forMoney(), entry(), startingChips(),
                    tournamentHands(), minimumWager(), maximumWager(), prizeSplit()));
            case BANKER -> specs.addAll(List.of(forMoney(), fixedBet(), bankerPlays(), ties()));
        }
        specs.addAll(List.of(decks(), dealerHitsSoft17(), naturalPayout()));
        if (format != BlackjackFormat.TOURNAMENT) {
            specs.addAll(List.of(buyInMinimum(), buyInMaximum()));
        }
        return List.copyOf(specs);
    }

    /**
     * Against the house and a player bank, a stack to play from. In a
     * tournament, exactly the entry: it is set aside when the player sits
     * and is what the tournament takes or pays at the end. Nothing at all
     * when the game is played for fun.
     */
    @Override
    public Optional<BuyIn> buyIn(TableSettings settings) {
        return switch (format) {
            case HOUSE -> Optional.of(new BuyIn(settings.get(buyInMinimum()), settings.get(buyInMaximum())));
            case TOURNAMENT -> playsForMoney(settings)
                    ? Optional.of(new BuyIn(settings.get(entry()), settings.get(entry())))
                    : Optional.empty();
            case BANKER -> playsForMoney(settings)
                    ? Optional.of(new BuyIn(settings.get(buyInMinimum()), settings.get(buyInMaximum())))
                    : Optional.empty();
        };
    }

    @Override
    public Optional<String> settingsProblem(TableSettings settings) {
        if (format != BlackjackFormat.BANKER) {
            long minimum = settings.get(minimumWager());
            long maximum = settings.get(maximumWager());
            if (maximum != BlackjackLimits.UNLIMITED && maximum < minimum) {
                return Optional.of("tablegames.setting.problem.bet_inverted");
            }
        }
        if (format == BlackjackFormat.TOURNAMENT) {
            // Nobody could place the first bet.
            if (settings.get(minimumWager()) > settings.get(startingChips())) {
                return Optional.of("tablegames.setting.problem.min_bet_above_chips");
            }
            return Optional.empty();
        }
        long buyInMaximum = settings.get(buyInMaximum());
        if (buyInMaximum != BuyIn.UNLIMITED && buyInMaximum < settings.get(buyInMinimum())) {
            return Optional.of("tablegames.setting.problem.buy_in_inverted");
        }
        // A stack that cannot cover the smallest wager seats somebody who can
        // only watch.
        if (settings.get(buyInMinimum()) < limitsFrom(settings).minimum()) {
            return Optional.of("tablegames.setting.problem.buy_in_below_bet");
        }
        return Optional.empty();
    }

    /** Whether credits change hands. Always, against the house. */
    public boolean playsForMoney(TableSettings settings) {
        return format.isHouse() || forMoney().isOn(settings.get(forMoney()));
    }

    /**
     * The opening wagers a table set up this way takes, in credits or in
     * tournament chips. Against a player bank, the one wager everybody plays
     * for. Callers clear {@link #settingsProblem} first.
     */
    public BlackjackLimits limitsFrom(TableSettings settings) {
        if (format == BlackjackFormat.BANKER) {
            long bet = settings.get(fixedBet());
            return new BlackjackLimits(bet, bet);
        }
        return new BlackjackLimits(settings.get(minimumWager()), settings.get(maximumWager()));
    }

    /** The rules a table set up this way plays. */
    public BlackjackRules rulesFrom(TableSettings settings) {
        return new BlackjackRules(
                DECK_COUNTS.get((int) settings.get(decks())),
                dealerHitsSoft17().isOn(settings.get(dealerHitsSoft17())),
                NaturalPayout.values()[(int) settings.get(naturalPayout())]);
    }

    /** What each player pays to enter a tournament: zero when it is played for fun. */
    public long entryFrom(TableSettings settings) {
        return playsForMoney(settings) ? settings.get(entry()) : 0;
    }

    public long startingChipsFrom(TableSettings settings) {
        return settings.get(startingChips());
    }

    public int handsFrom(TableSettings settings) {
        return TOURNAMENT_HANDS.get((int) settings.get(tournamentHands()));
    }

    public PrizeSplit prizeSplitFrom(TableSettings settings) {
        return PrizeSplit.values()[(int) settings.get(prizeSplit())];
    }

    public boolean bankerPlaysByHand(TableSettings settings) {
        return settings.get(bankerPlays()) == BANKER_PLAYS.indexOf("by_hand");
    }

    public boolean bankerWinsTies(TableSettings settings) {
        return settings.get(ties()) == TIES.indexOf("banker");
    }

    // --- Sessions ------------------------------------------------------------------------

    /**
     * A hand at the standard rules from a fresh shoe, every seat wagering the
     * default minimum against the house. Satisfies the interface; a table
     * uses the overload, which knows its shoe, its rules, what each seat
     * actually put down and who holds the bank.
     */
    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        Map<UUID, Long> bets = new LinkedHashMap<>();
        for (Seat seat : seats) {
            bets.put(seat.playerId(), DEFAULT_MINIMUM_BET);
        }
        return createSession(seats, random, BlackjackRules.STANDARD,
                new Shoe(BlackjackRules.STANDARD.decks(), random), bets, null);
    }

    /**
     * @param banker the player holding the bank, or null for the house's
     *               dealer — which in a tournament stakes nothing at all
     */
    public BlackjackSession createSession(List<Seat> seats, RandomGenerator random,
                                          BlackjackRules rules, Shoe shoe,
                                          Map<UUID, Long> openingBets, BlackjackBanker banker) {
        // A player bank is a second player at the table, so a single seat
        // dealt in against it is a game; the seat count alone may be one.
        if (seats.isEmpty() || seats.size() > maxPlayers()) {
            throw new IllegalArgumentException(
                    "Blackjack deals 1 to " + maxPlayers() + " seats, got " + seats.size());
        }
        return new BlackjackSession(seats, random, rules, shoe, openingBets, banker);
    }
}
