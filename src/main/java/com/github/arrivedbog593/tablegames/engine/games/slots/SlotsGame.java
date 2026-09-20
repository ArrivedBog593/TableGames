package com.github.arrivedbog593.tablegames.engine.games.slots;

import com.github.arrivedbog593.tablegames.engine.economy.CreditAccount;
import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A three-reel slot machine with five lines, played alone against the house.
 * <p>
 * The reels never change. What a table chooses is how generous the machine
 * is, from a short list of paytables whose returns are counted exactly by
 * {@link SlotMachine#returnToPlayer}; the tests pin each one to the figure it
 * is labelled with. Real machines are set the same way: the same reels,
 * a different card on the glass.
 */
public final class SlotsGame implements Game {

    /**
     * The paytables a machine may run, and the return each one is labelled
     * with, loosest last.
     * <p>
     * Worked out from the 95 card rather than each on its own, so the ladder
     * holds one promise a player can check: <em>no machine ever pays less
     * than a tighter one</em>. Every multiple below is at least the multiple
     * above it, which is not free — the return is a weighted sum of six
     * integers, and landing eight targets inside a tenth of a point while
     * keeping that order took solving the ladder outwards from the middle.
     * <p>
     * The shape that fell out of it is the one a real floor has. Tightening
     * a machine takes the small, frequent wins down — three coal drops from
     * seven to five between 95 and 85 — while the jackpot stays where it is,
     * because the jackpot is the advertisement and nobody is drawn across a
     * room by a good price on coal. Loosening past 95 does the opposite and
     * only grows the top prize: a 97 pays exactly what a 95 does on
     * everything else.
     * <p>
     * Each level is pinned to its label by a test that counts the return
     * over all 32768 ways the reels can stop. A card that does not pay what
     * it says fails the build.
     */
    public enum Payback {
        P85("85", 5, 12, 25, 63, 125, 661),
        P88("88", 5, 13, 27, 64, 130, 662),
        P90("90", 6, 13, 27, 66, 130, 664),
        P92("92", 6, 14, 27, 67, 138, 668),
        P94("94", 7, 14, 27, 69, 138, 670),
        P95("95", 7, 14, 28, 70, 140, 670),
        P96("96", 7, 14, 28, 70, 140, 710),
        P97("97", 7, 14, 28, 70, 140, 751);

        /** Coal on the first two reels pays the same at every level. */
        private static final int TWO_COAL = 2;

        private final String percent;
        private final Paytable paytable;

        Payback(String percent, int coal, int iron, int gold, int emerald, int diamond,
                int netherite) {
            this.percent = percent;
            Map<SlotSymbol, Integer> threes = new EnumMap<>(SlotSymbol.class);
            threes.put(SlotSymbol.COAL, coal);
            threes.put(SlotSymbol.IRON, iron);
            threes.put(SlotSymbol.GOLD, gold);
            threes.put(SlotSymbol.EMERALD, emerald);
            threes.put(SlotSymbol.DIAMOND, diamond);
            threes.put(SlotSymbol.NETHERITE, netherite);
            this.paytable = new Paytable(threes, TWO_COAL);
        }

        /** The return this level is labelled with, in whole percent. */
        public String percent() {
            return percent;
        }

        public Paytable paytable() {
            return paytable;
        }
    }

    private static final Payback DEFAULT_PAYBACK = Payback.P95;

    /** Credits a player must buy before the machine will take a pull. */
    private static final long DEFAULT_BUY_IN_MINIMUM = 10;

    /**
     * What one credit costs when nobody has said otherwise.
     * <p>
     * One, so a machine left alone behaves as if credits had never been
     * invented: what a line costs is what it costs.
     */
    private static final long DEFAULT_DENOMINATION = 1;

    /**
     * The strip every reel is cut from, as letters: Coal, Iron, Gold,
     * Emerald, Diamond, Netherite, Replay. Eight coal down to two netherite
     * and two replays in thirty-two stops, spread so the rare symbols never
     * sit side by side.
     */
    private static final String STRIP = "CIGCEICDGICNGCIRGCEIDCGIECNGIDER";

    private final String id;
    private final List<Reel> reels;

    private SlotsGame(String id, List<Reel> reels) {
        this.id = id;
        this.reels = List.copyOf(reels);
    }

    /**
     * The machine this mod ships. The second reel runs the strip backwards
     * and the third starts it elsewhere, so the three windows never scroll
     * past in step.
     */
    public static SlotsGame standard() {
        String reversed = new StringBuilder(STRIP).reverse().toString();
        String shifted = STRIP.substring(7) + STRIP.substring(0, 7);
        return new SlotsGame("slots", List.of(reel(STRIP), reel(reversed), reel(shifted)));
    }

    private static Reel reel(String letters) {
        List<SlotSymbol> stops = new ArrayList<>(letters.length());
        for (char letter : letters.toCharArray()) {
            stops.add(switch (letter) {
                case 'C' -> SlotSymbol.COAL;
                case 'I' -> SlotSymbol.IRON;
                case 'G' -> SlotSymbol.GOLD;
                case 'E' -> SlotSymbol.EMERALD;
                case 'D' -> SlotSymbol.DIAMOND;
                case 'N' -> SlotSymbol.NETHERITE;
                case 'R' -> SlotSymbol.REPLAY;
                default -> throw new IllegalArgumentException("Not a symbol: " + letter);
            });
        }
        return new Reel(stops);
    }

    @Override
    public String id() {
        return id;
    }

    public List<Reel> reels() {
        return reels;
    }

    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public int maxPlayers() {
        return 1;
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
        return 1;
    }

    // --- Configuration -------------------------------------------------------

    /**
     * What one credit costs, in the credits a player's balance is kept in.
     * <p>
     * The unit everything else about this machine is quoted in. A cabinet set
     * to twenty-five takes twenty-five off a balance for each credit bought,
     * and from there the player thinks in credits: a line costs one, three
     * coal pays seven, and what that is worth is the same multiplication done
     * once at the door instead of on every figure on the screen.
     * <p>
     * This is how a real floor is laid out — the same machine at a penny and
     * at a dollar — and it is why a house can put a cheap cabinet by the
     * entrance and an expensive one at the back without touching a paytable.
     * The return is a ratio, so it is identical on both.
     */
    public SettingSpec.Amount denomination() {
        return new SettingSpec.Amount(id + ".denomination", DEFAULT_DENOMINATION, 1,
                CreditAccount.MAX_BALANCE);
    }

    /** The least a line may be played for, in credits. */
    public SettingSpec.Amount betMinimum() {
        return new SettingSpec.Amount(id + ".bet_min", 1, 1, CreditAccount.MAX_BALANCE);
    }

    /** The most, in credits, where zero leaves the bankroll alone to decide. */
    public SettingSpec.Amount betMaximum() {
        return new SettingSpec.Amount(id + ".bet_max", 0, 0, CreditAccount.MAX_BALANCE);
    }

    /** Which paytable the machine runs, and so how much it returns. */
    public SettingSpec.Choice payback() {
        List<String> options = new ArrayList<>();
        for (Payback level : Payback.values()) {
            options.add(level.percent());
        }
        return new SettingSpec.Choice(id + ".payback", options, DEFAULT_PAYBACK.ordinal());
    }

    /** The fewest credits a player may buy at once, in credits. */
    public SettingSpec.Amount buyInMinimum() {
        return new SettingSpec.Amount(id + ".buy_in_min", DEFAULT_BUY_IN_MINIMUM, 1,
                CreditAccount.MAX_BALANCE);
    }

    /** The most credits a meter may hold, in credits; zero for no cap. */
    public SettingSpec.Amount buyInMaximum() {
        return new SettingSpec.Amount(id + ".buy_in_max", BuyIn.UNLIMITED, BuyIn.UNLIMITED,
                CreditAccount.MAX_BALANCE);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(denomination(), payback(), betMinimum(), betMaximum(),
                buyInMinimum(), buyInMaximum());
    }

    @Override
    public Optional<String> settingsProblem(TableSettings settings) {
        long betMax = settings.get(betMaximum());
        if (betMax != 0 && betMax < settings.get(betMinimum())) {
            return Optional.of("tablegames.setting.problem.bet_inverted");
        }
        long buyInMax = settings.get(buyInMaximum());
        if (buyInMax != BuyIn.UNLIMITED && buyInMax < settings.get(buyInMinimum())) {
            return Optional.of("tablegames.setting.problem.buy_in_inverted");
        }
        // Credits that cannot pay for one line would seat somebody who can
        // only watch the reels. Both figures are in credits, so the
        // denomination cancels and does not come into it.
        if (settings.get(buyInMinimum()) < settings.get(betMinimum())) {
            return Optional.of("tablegames.setting.problem.buy_in_below_bet");
        }
        return Optional.empty();
    }

    /**
     * What a player must buy to play, quoted in the balance rather than in
     * credits.
     * <p>
     * The conversion happens here because this is where the denomination is
     * understood. Everything outside — the seat, the stack, the commitments,
     * the bankroll — deals in one currency, the one a balance is kept in, and
     * has no business knowing that this particular block counts in tens.
     */
    @Override
    public Optional<BuyIn> buyIn(TableSettings settings) {
        long each = settings.get(denomination());
        long maximum = settings.get(buyInMaximum());
        return Optional.of(new BuyIn(
                Math.multiplyExact(settings.get(buyInMinimum()), each),
                maximum == BuyIn.UNLIMITED ? BuyIn.UNLIMITED : Math.multiplyExact(maximum, each)));
    }

    /** What one credit costs at a machine set up this way. */
    public long denominationFrom(TableSettings settings) {
        return Math.max(1, settings.get(denomination()));
    }

    /** What this many credits costs, in the currency a balance is kept in. */
    public long priceOf(long credits, TableSettings settings) {
        return Math.multiplyExact(credits, denominationFrom(settings));
    }

    /** The paytable a table set up this way runs. */
    public Payback paybackFrom(TableSettings settings) {
        return Payback.values()[(int) settings.get(payback())];
    }

    /** The machine a table set up this way plays. */
    public SlotMachine machineFor(TableSettings settings) {
        return new SlotMachine(reels, paybackFrom(settings).paytable());
    }

    @Override
    public GameSession createSession(List<Seat> seats, RandomGenerator random) {
        return new SlotsSession(seats, random, new SlotMachine(reels, DEFAULT_PAYBACK.paytable()));
    }

    /** A spin on the machine this table is set up to run. */
    public SlotsSession createSession(List<Seat> seats, RandomGenerator random,
                                      SlotMachine machine) {
        return new SlotsSession(seats, random, machine);
    }
}
