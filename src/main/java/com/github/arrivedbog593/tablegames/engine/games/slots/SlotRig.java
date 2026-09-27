package com.github.arrivedbog593.tablegames.engine.games.slots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where to stop a machine's reels so that it lands a chosen result.
 * <p>
 * For testing payouts, and nothing else. It does not change what a result
 * pays — it finds stops on the real reels that produce the result, and the
 * spin is then read off those stops by {@link SlotMachine#resultAt}, the same
 * as any other. A tester asking for three diamonds gets exactly what three
 * diamonds pay on this machine's card, and hears exactly what the room would.
 * <p>
 * Every result is looked for on the middle line, because that is the one line
 * every pull plays. Among the stops that put it there, the search prefers the
 * ones where no other line pays, so that what a tester sees on the meter is
 * the result they asked for and not that plus an accident on a diagonal.
 */
public final class SlotRig {

    private SlotRig() {
    }

    /** What kind of result a target is. */
    public enum Kind {

        /** Three of one symbol on the middle line. Three replays is the free spin. */
        THREE,

        /** Coal on the first two reels and not the third: the smallest win. */
        TWO_COAL,

        /** Nothing on any line. */
        NOTHING
    }

    /**
     * A result a tester can ask for.
     *
     * @param id     stable name, for the wire and translation keys
     * @param kind   what shape of result it is
     * @param symbol the symbol it is made of, or null for nothing
     */
    public record Target(String id, Kind kind, SlotSymbol symbol) {
    }

    /**
     * Every result worth asking for on this card, best paying first: each
     * three of a kind, two coal when it pays, the free spin, and nothing.
     */
    public static List<Target> targets(Paytable paytable) {
        List<Target> targets = new ArrayList<>();
        paytable.threeOfAKind().entrySet().stream()
                .sorted(Map.Entry.<SlotSymbol, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> targets.add(
                        new Target(entry.getKey().id(), Kind.THREE, entry.getKey())));
        if (paytable.twoCoal() > 0) {
            targets.add(new Target("two_coal", Kind.TWO_COAL, SlotSymbol.COAL));
        }
        targets.add(new Target(SlotSymbol.REPLAY.id(), Kind.THREE, SlotSymbol.REPLAY));
        targets.add(new Target("nothing", Kind.NOTHING, null));
        return targets;
    }

    /** The target with this id, if the card has one. */
    public static Optional<Target> target(Paytable paytable, String id) {
        return targets(paytable).stream().filter(target -> target.id().equals(id)).findFirst();
    }

    /**
     * Stops that land this target on the middle line, with as little else
     * paying as the reels allow; empty when the reels cannot show it at all.
     * <p>
     * Tries every combination of stops. Three reels of a few dozen stops is a
     * few tens of thousands of evaluations, done once when somebody asks, and
     * an exhaustive search is the only kind that can honestly answer "these
     * reels cannot do that".
     */
    public static Optional<int[]> stopsFor(SlotMachine machine, Target target) {
        List<Reel> reels = machine.reels();
        int[] best = null;
        int bestExtra = Integer.MAX_VALUE;
        int[] stops = new int[SlotMachine.REELS];
        for (stops[0] = 0; stops[0] < reels.get(0).size(); stops[0]++) {
            for (stops[1] = 0; stops[1] < reels.get(1).size(); stops[1]++) {
                for (stops[2] = 0; stops[2] < reels.get(2).size(); stops[2]++) {
                    SlotMachine.SpinResult result = machine.resultAt(stops, Payline.MAX);
                    if (!lands(result, target)) {
                        continue;
                    }
                    int extra = extraLines(result, target);
                    if (extra < bestExtra) {
                        best = stops.clone();
                        bestExtra = extra;
                        if (extra == 0) {
                            return Optional.of(best);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether the middle line shows what the target asks for. */
    static boolean lands(SlotMachine.SpinResult result, Target target) {
        List<SlotSymbol> middle = result.along(Payline.MIDDLE);
        return switch (target.kind()) {
            case THREE -> middle.stream().allMatch(target.symbol()::equals);
            case TWO_COAL -> middle.get(0) == SlotSymbol.COAL
                    && middle.get(1) == SlotSymbol.COAL
                    && middle.get(2) != SlotSymbol.COAL;
            case NOTHING -> result.wins().isEmpty();
        };
    }

    /** How many lines besides the middle one pay. */
    private static int extraLines(SlotMachine.SpinResult result, Target target) {
        int extra = 0;
        for (Payline line : result.wins().keySet()) {
            if (line != Payline.MIDDLE || target.kind() == Kind.NOTHING) {
                extra++;
            }
        }
        return extra;
    }
}
