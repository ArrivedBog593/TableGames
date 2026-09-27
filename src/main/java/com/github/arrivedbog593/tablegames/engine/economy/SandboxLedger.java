package com.github.arrivedbog593.tablegames.engine.economy;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A pretend economy for one test table: a bank and a balance per player that
 * exist nowhere else.
 * <p>
 * The whole of the real money path is here in miniature — the same
 * {@link SettlementAudit} decides what a hand may do, the same bankroll rules
 * derive the table's limits from the bank, the same exposure and commitment
 * bookkeeping keeps the table from promising more than it has — so a tester
 * sees exactly how a table behaves with ten million in the house and fifty
 * thousand in their pocket. None of it reaches the real bankroll, the real
 * balances, or the transaction log: there is nothing here to write, nothing
 * to recover on a restart, and nothing that survives one.
 * <p>
 * Each player starts at the same balance the first time the table asks about
 * them, so two testers at one table each get the figure it was set up with.
 */
public final class SandboxLedger {

    private long bank;
    private final long startingBalance;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final HouseExposure exposure = new HouseExposure();
    private final PlayerCommitments stakes = new PlayerCommitments();

    /**
     * @param bank            what the pretend house holds
     * @param startingBalance what each player starts with
     */
    public SandboxLedger(long bank, long startingBalance) {
        if (bank < 0 || bank > CreditAccount.MAX_BALANCE) {
            throw new IllegalArgumentException("Bank out of range: " + bank);
        }
        if (startingBalance < 0 || startingBalance > CreditAccount.MAX_BALANCE) {
            throw new IllegalArgumentException("Starting balance out of range: " + startingBalance);
        }
        this.bank = bank;
        this.startingBalance = startingBalance;
    }

    /** What the pretend house holds now. */
    public long bank() {
        return bank;
    }

    /** What every player started with. */
    public long startingBalance() {
        return startingBalance;
    }

    /** What this player holds now, or the starting balance if they have not played. */
    public long balanceOf(UUID playerId) {
        return balances.getOrDefault(playerId, startingBalance);
    }

    /** What the tables here stand to lose; only this table's. */
    public HouseExposure exposure() {
        return exposure;
    }

    /** What each player has promised; only to this table. */
    public PlayerCommitments stakes() {
        return stakes;
    }

    /**
     * The house as this table sees it: the real house's rules — how much of
     * the bank may be at risk, what reserve it keeps — over the pretend bank.
     * So the limits a tester sees move exactly as they would on the floor.
     */
    public HouseBankroll bankroll(HouseBankroll policy) {
        return policy.withBalance(bank);
    }

    /**
     * Settles a finished hand against the pretend accounts.
     * <p>
     * All or nothing, like the real one: audited first, every loser checked
     * for the credits and every winner for room under the cap, and only then
     * anything moved. A refusal leaves every figure where it was.
     *
     * @return what the audit decided, with a refusal's reason when it refused
     */
    public SettlementAudit.Verdict settle(Outcome outcome, boolean houseBanked, HouseBankroll policy) {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(outcome, houseBanked, bankroll(policy));
        if (!verdict.approved()) {
            return verdict;
        }
        for (Payout payout : outcome.payouts()) {
            long held = balanceOf(payout.playerId());
            if (payout.delta() < 0 && held < -payout.delta()) {
                return new SettlementAudit.Verdict(false,
                        "tablegames.settle.player_cannot_cover", 0, -payout.delta());
            }
            if (payout.delta() > 0 && !CreditAccount.canHold(held, payout.delta())) {
                return new SettlementAudit.Verdict(false, "tablegames.settle.player_cap_reached", 0, 0);
            }
        }
        for (Payout payout : outcome.payouts()) {
            balances.put(payout.playerId(), balanceOf(payout.playerId()) + payout.delta());
        }
        bank += verdict.houseDelta();
        return verdict;
    }
}
