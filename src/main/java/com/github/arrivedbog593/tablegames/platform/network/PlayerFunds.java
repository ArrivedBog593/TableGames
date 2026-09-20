package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

/**
 * What the viewer has at one block, and what of it is already spoken for.
 * <p>
 * Shared by every game that takes credits, because the question is the same
 * wherever it is asked: a roulette felt and a slot machine both need to
 * know what this player may still stake here, and both have the same three
 * things standing in the way — what they own, what other blocks are
 * holding, and what they have already fed into this one.
 * <p>
 * A record of its own rather than fields on each state payload, because
 * {@code StreamCodec.composite} stops at six components and both views were
 * using them. Grouping is cheaper than a handwritten codec and says
 * something true besides: these numbers are only meaningful together.
 *
 * @param balance   the viewer's credits, never anybody else's
 * @param elsewhere what of that balance is riding on other blocks, so the
 *                  screen can grey out a stake this one would refuse.
 *                  Without it, a player with credits committed across the
 *                  room sees every chip lit and finds out by clicking.
 *                  What they have staked <em>here</em> is not in it: the
 *                  screen already shows that separately, and this block's
 *                  rules leave room for the wagers it is holding
 * @param stack        what the viewer bought in with here, as it stands
 * @param buyInMinimum the least a player sits down with, or zero when this
 *                     block takes wagers straight from the balance
 * @param buyInMaximum the most a stack may hold, zero for no ceiling
 * @param denomination what one of this block's own credits costs, or one
 *                     where a block has none of its own. Every figure above
 *                     is in the balance's currency whatever this says; see
 *                     {@link #inCredits(long)}
 */
public record PlayerFunds(long balance, long elsewhere, long stack,
                          long buyInMinimum, long buyInMaximum, long denomination) {

    public static final StreamCodec<ByteBuf, PlayerFunds> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, PlayerFunds::balance,
                    ByteBufCodecs.VAR_LONG, PlayerFunds::elsewhere,
                    ByteBufCodecs.VAR_LONG, PlayerFunds::stack,
                    ByteBufCodecs.VAR_LONG, PlayerFunds::buyInMinimum,
                    ByteBufCodecs.VAR_LONG, PlayerFunds::buyInMaximum,
                    ByteBufCodecs.VAR_LONG, PlayerFunds::denomination,
                    PlayerFunds::new);

    /** What one credit costs here, never less than one. */
    public long each() {
        return Math.max(1, denomination);
    }

    /** Whether this block counts in credits of its own rather than in balance. */
    public boolean priced() {
        return each() > 1;
    }

    /**
     * This much of a balance, counted in the block's own credits.
     * <p>
     * Rounds down, and has to: a player with enough for nine credits and
     * change can buy nine. The change is not lost — it never leaves their
     * balance, because only whole credits are ever bought.
     */
    public long inCredits(long amount) {
        return amount / each();
    }

    /** This many of the block's credits, priced in the balance's currency. */
    public long inBalance(long credits) {
        return credits * each();
    }

    public boolean hasBuyIn() {
        return buyInMinimum > 0;
    }

    /**
     * The buy-in rule as the player is asked it: in credits at a block that
     * counts in them, and in balance at one that does not.
     * <p>
     * Both ends divide exactly. A machine's buy-in is set in credits and
     * multiplied up on the way here, so dividing it back is the same number
     * and not a rounding.
     */
    public Optional<BuyIn> buyIn() {
        if (!hasBuyIn()) {
            return Optional.empty();
        }
        return Optional.of(new BuyIn(inCredits(buyInMinimum),
                buyInMaximum == BuyIn.UNLIMITED ? BuyIn.UNLIMITED : inCredits(buyInMaximum)));
    }

    /**
     * The most the viewer may have riding here in total: their stack, or
     * where there is no buy-in, whatever other blocks are not holding.
     * <p>
     * In the balance's currency, because this answers what may be staked and
     * a stake is settled in the balance.
     */
    public long placeable() {
        return hasBuyIn() ? stack : Math.max(0L, balance - elsewhere);
    }

    /** What the viewer could still reserve here, for a buy-in or a top-up. */
    public long available() {
        return Math.max(0L, balance - elsewhere - stack);
    }

    /** The same, in whatever unit {@link #buyIn()} is quoted in. */
    public long availableToBuy() {
        return inCredits(available());
    }

    /** The stack, in whatever unit {@link #buyIn()} is quoted in. */
    public long stackHeld() {
        return inCredits(stack);
    }
}
