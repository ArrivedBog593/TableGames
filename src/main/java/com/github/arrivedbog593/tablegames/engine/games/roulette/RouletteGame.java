package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.economy.CreditAccount;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Roulette, in whichever wheel variant it was configured with.
 * <p>
 * European and American are the same game with a different wheel, so they are
 * registered as two instances rather than two classes. A server can run both
 * at once: the table block stores which game id it hosts, so one table can be
 * European and the table beside it American.
 * <p>
 * A record because a game is configuration and a factory, never a hand in
 * progress. Everything that changes during a spin lives in
 * {@link RouletteSession}, and having no room to store it here is the point.
 *
 * @param id     registry id, persisted in table block entities
 * @param wheel  which pockets the ball can land in
 * @param limits what a table hosting this game takes when nobody configured
 *               it: a small minimum and no ceiling of the game's own. The
 *               maximum used to be a flat ten thousand, which nobody chose
 *               for any reason and which quietly overrode the limit derived
 *               from the bankroll — a house with a hundred million could
 *               afford a hundred and thirty-eight thousand on a single number
 *               and would still refuse anything over ten. A game does not
 *               know how much the house has, so it is in no position to set
 *               the number: the bankroll caps what can be paid, and the
 *               table's own limits cap what it chooses to take
 */
public record RouletteGame(String id, RouletteWheel wheel, BetLimits limits)
        implements Game {

    private static final int MAX_SEATS = 8;

    /**
     * The least a player sits down with when nobody set a figure. Ten
     * minimum bets' worth at the default limits: enough to play a few
     * rounds, not so much that a small table turns people away.
     */
    private static final long DEFAULT_BUY_IN_MINIMUM = 100;

    public RouletteGame {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(wheel, "wheel");
        Objects.requireNonNull(limits, "limits");
    }

    /** Single zero, 2.70% house edge. The friendlier default. */
    public static RouletteGame european() {
        return new RouletteGame("roulette", RouletteWheel.EUROPEAN, BetLimits.DEFAULT);
    }

    /** Zero and double zero, 5.26% house edge. */
    public static RouletteGame american() {
        return new RouletteGame("american_roulette", RouletteWheel.AMERICAN,
                BetLimits.DEFAULT);
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

    /**
     * The smallest wager the game takes anywhere on the layout.
     * <p>
     * Inside and outside bets have their own minimums, so a single figure can
     * only be the lower of the two. Anything that needs to know what a
     * particular bet costs must ask {@link BetLimits#minimumFor} instead.
     */
    @Override
    public long minimumBet() {
        return Math.min(limits.insideMinimum(), limits.outsideMinimum());
    }

    // --- Configuration -------------------------------------------------------

    /** The smallest wager a table may be set to take on an inside bet. */
    public SettingSpec.Amount insideMinimum() {
        return minimumSpec("inside_min", limits.insideMinimum());
    }

    /** The largest, where zero posts no ceiling of the table's own. */
    public SettingSpec.Amount insideMaximum() {
        return maximumSpec("inside_max", limits.insideMaximum());
    }

    public SettingSpec.Amount outsideMinimum() {
        return minimumSpec("outside_min", limits.outsideMinimum());
    }

    public SettingSpec.Amount outsideMaximum() {
        return maximumSpec("outside_max", limits.outsideMaximum());
    }

    /** The least a player may sit down with. */
    public SettingSpec.Amount buyInMinimum() {
        return minimumSpec("buy_in_min", DEFAULT_BUY_IN_MINIMUM);
    }

    /** The most a stack may hold, where zero lets a player bring whatever they have. */
    public SettingSpec.Amount buyInMaximum() {
        return new SettingSpec.Amount(
                id + ".buy_in_max", BuyIn.UNLIMITED, BuyIn.UNLIMITED, CreditAccount.MAX_BALANCE);
    }

    /**
     * The numbers a roulette table posts, in the order a screen should ask
     * for them: each minimum immediately before the maximum it pairs with,
     * the wager limits first and the buy-in after.
     */
    @Override
    public List<SettingSpec> settings() {
        return List.of(insideMinimum(), insideMaximum(),
                outsideMinimum(), outsideMaximum(),
                buyInMinimum(), buyInMaximum());
    }

    /**
     * Every seated player brings a stack. That is what gives "all in" a
     * meaning short of the player's whole balance.
     */
    @Override
    public Optional<BuyIn> buyIn(TableSettings settings) {
        return Optional.of(new BuyIn(settings.get(buyInMinimum()), settings.get(buyInMaximum())));
    }

    /**
     * Whether the posted ceilings clear the floors they sit above.
     * <p>
     * Neither maximum can judge this alone: a ceiling of fifty is perfectly
     * legal on its own and nonsense under a floor of a hundred. A table that
     * stored the pair anyway would take no wager at all on half its layout.
     */
    @Override
    public Optional<String> settingsProblem(TableSettings settings) {
        if (belowItsFloor(settings, insideMinimum(), insideMaximum())) {
            return Optional.of("tablegames.setting.problem.inside_inverted");
        }
        if (belowItsFloor(settings, outsideMinimum(), outsideMaximum())) {
            return Optional.of("tablegames.setting.problem.outside_inverted");
        }
        if (belowItsFloor(settings, buyInMinimum(), buyInMaximum())) {
            return Optional.of("tablegames.setting.problem.buy_in_inverted");
        }
        // A stack that cannot cover a single bet anywhere on the layout would
        // seat people who can do nothing but watch.
        long cheapestBet = Math.min(settings.get(insideMinimum()), settings.get(outsideMinimum()));
        if (settings.get(buyInMinimum()) < cheapestBet) {
            return Optional.of("tablegames.setting.problem.buy_in_below_bet");
        }
        return Optional.empty();
    }

    /**
     * The limits a table set up this way posts.
     * <p>
     * Callers must have cleared {@link #settingsProblem} first: this builds a
     * {@link BetLimits}, which refuses an inverted pair outright.
     */
    public BetLimits limitsFrom(TableSettings settings) {
        return new BetLimits(
                settings.get(insideMinimum()), settings.get(insideMaximum()),
                settings.get(outsideMinimum()), settings.get(outsideMaximum()));
    }

    /**
     * A table cannot be configured to take, or pay, more than the economy can
     * ever hold. {@link Long#MAX_VALUE} here would let a posted limit sit
     * where {@code amount * payoutRatio} overflows the moment a bet against
     * it settles — the check exists to refuse the wager, not to be the thing
     * that breaks the arithmetic doing the refusing.
     */
    private SettingSpec.Amount minimumSpec(String name, long fallback) {
        return new SettingSpec.Amount(id + "." + name, fallback, 1, CreditAccount.MAX_BALANCE);
    }

    private SettingSpec.Amount maximumSpec(String name, long fallback) {
        return new SettingSpec.Amount(
                id + "." + name, fallback, BetLimits.UNLIMITED, CreditAccount.MAX_BALANCE);
    }

    private static boolean belowItsFloor(TableSettings settings,
                                         SettingSpec.Amount floor,
                                         SettingSpec.Amount ceiling) {
        long posted = settings.get(ceiling);
        return posted != BetLimits.UNLIMITED && posted < settings.get(floor);
    }

    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        return createSession(seats, random, limits);
    }

    /**
     * Creates a session bound to one table's limits.
     * <p>
     * The platform layer calls this, because the numbers a table posts are
     * the numbers the rules have to enforce. Building the session from the
     * game's own defaults instead left the two layers disagreeing about what
     * was legal: the block took a wager under its configured minimum, and the
     * session refused the same wager at the spin, which drops a stake that a
     * player watched being accepted.
     */
    public GameSession createSession(List<Seat> seats, RandomGenerator random,
                                     BetLimits tableLimits) {
        if (!canStartWith(seats.size())) {
            throw new IllegalArgumentException(
                    "Roulette needs " + minPlayers() + " to " + maxPlayers()
                            + " players, got " + seats.size());
        }
        return new RouletteSession(seats, random, wheel, tableLimits);
    }
}