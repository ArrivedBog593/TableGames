package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.economycore.api.Bank;
import com.github.arrivedbog593.economycore.api.CreditFormat;
import com.github.arrivedbog593.economycore.api.Credits;
import com.github.arrivedbog593.tablegames.engine.house.HouseBankroll;
import com.github.arrivedbog593.tablegames.engine.house.HouseExposure;
import com.github.arrivedbog593.tablegames.platform.house.HouseSettings;
import com.github.arrivedbog593.tablegames.platform.house.HouseStaff;
import com.github.arrivedbog593.tablegames.platform.house.OutcomeSettler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Managing the casino's bankroll.
 * <p>
 * The bankroll starts empty and is seeded here. That is deliberate: a casino
 * that opens with invented funds is a casino that mints credits, and the
 * operator putting real credits behind the tables is what makes every payout
 * afterward honest.
 */
public final class HouseCommands {

    /** Roulette's straight-up bet, quoted in the status report as the worst case. */
    private static final int WORST_CASE_RATIO = 35;

    private HouseCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The root carries no requirement, and each branch below carries its
        // own. Brigadier merges same-named roots but keeps the requirement of
        // whichever was registered first, so one gated root here would have
        // gated every other class's commands too — including the one branch
        // that has to work without operator rights.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames");

        root.then(Commands.literal("house")
                .requires(HouseStaff::mayRunTheHouse)
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("exposure")
                        .executes(context -> showExposure(context.getSource()))
                        .then(Commands.argument("percent", IntegerArgumentType.integer(1, 100))
                                .executes(context -> exposure(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "percent")))))
                .then(Commands.literal("reserve")
                        .executes(context -> showReserve(context.getSource()))
                        .then(Commands.argument("credits",
                                        LongArgumentType.longArg(0, Credits.MAX_BALANCE))
                                .executes(context -> reserve(context.getSource(),
                                        LongArgumentType.getLong(context, "credits")))))

                .then(Commands.literal("plan")
                        .then(Commands.argument("maxbet",
                                        LongArgumentType.longArg(1, Credits.MAX_BALANCE))
                                .executes(context -> plan(context.getSource(),
                                        LongArgumentType.getLong(context, "maxbet"))))));

        dispatcher.register(root);
    }

    /**
     * The full picture: balance, health, and what it allows at the tables.
     * <p>
     * Reports the derived limits rather than just the number, because a bare
     * balance tells an operator nothing about whether their casino is safe.
     */
    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        HouseBankroll bankroll = OutcomeSettler.bankroll(server);

        ChatFormatting color = switch (bankroll.status()) {
            case HEALTHY -> ChatFormatting.GREEN;
            case LOW -> ChatFormatting.YELLOW;
            case CLOSED -> ChatFormatting.RED;
        };

        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.balance", format(bankroll.balance())).withStyle(color), false);

        if (!bankroll.isOpen()) {
            // Two ways to be closed and two things to do about it. Below the
            // operator's own floor, the answer is to put credits in. Below
            // what the tables are holding, the answer is to wait for the
            // rounds in flight to settle — and printing the same line for
            // both would have somebody funding a bank that was about to
            // recover on its own.
            boolean owedMoreThanItHolds =
                    bankroll.minimumReserve() > HouseSettings.get(source.getServer()).minimumReserve();
            source.sendSuccess(() -> Component.translatable(owedMoreThanItHolds
                                    ? "tablegames.house.closed_committed"
                                    : "tablegames.house.closed",
                            format(bankroll.minimumReserve()))
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.exposure",
                bankroll.exposurePercent(),
                format(bankroll.maximumExposure())).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.limits",
                format(bankroll.maximumBet(WORST_CASE_RATIO)),
                format(bankroll.maximumBet(1))).withStyle(ChatFormatting.GRAY), false);

        // What is actually committed right now, across every open table. The
        // exposure ceiling above is what the house is willing to risk; this is
        // how much of it is already spoken for.
        int spread = Bank.exchangeSpreadPercent(source.getServer());
        if (spread > 0) {
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.house.spread_line", spread)
                    .withStyle(ChatFormatting.GRAY), false);
        }

        HouseExposure exposure = OutcomeSettler.exposure();
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.committed",
                format(exposure.total()),
                exposure.tableCount()).withStyle(ChatFormatting.GRAY), false);

        if (bankroll.status() == HouseBankroll.Status.LOW) {
            source.sendSuccess(() -> Component.translatable("tablegames.house.low")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // --- The settings, read back ------------------------------------------------
    //
    // Each setting answers when it is typed without a value, instead of the
    // command failing as incomplete: what it is now, what it is when nobody
    // has set it, and how to change it. Somebody about to change a limit is
    // the person who most needs to see the one in force.

    private static int showExposure(CommandSourceStack source) {
        int percent = HouseSettings.get(source.getServer()).exposurePercent();
        long perPayout = OutcomeSettler.bankroll(source.getServer()).maximumExposure();
        source.sendSuccess(() -> Component.translatable("tablegames.house.exposure_show",
                percent, format(perPayout), HouseBankroll.DEFAULT_EXPOSURE_PERCENT), false);
        return percent;
    }

    private static int showReserve(CommandSourceStack source) {
        long configured = HouseSettings.get(source.getServer()).minimumReserve();
        long effective = OutcomeSettler.bankroll(source.getServer()).minimumReserve();
        source.sendSuccess(() -> Component.translatable("tablegames.house.reserve_show",
                format(configured), format(HouseBankroll.DEFAULT_MINIMUM_RESERVE)), false);
        // The floor is raised to whatever the tables have riding, so the figure
        // that decides whether the house is open can be above the one typed.
        if (effective > configured) {
            source.sendSuccess(() -> Component.translatable("tablegames.house.reserve_raised",
                    format(effective)), false);
        }
        return (int) Math.min(Integer.MAX_VALUE, configured);
    }

    private static int exposure(CommandSourceStack source, int percent) {
        HouseSettings.get(source.getServer()).setExposurePercent(percent);
        announce(source, Component.translatable("tablegames.house.exposure_set", percent),
                Component.translatable("tablegames.house.exposure_set_by", source.getDisplayName(), percent));
        return status(source);
    }

    private static int reserve(CommandSourceStack source, long credits) {
        HouseSettings.get(source.getServer()).setMinimumReserve(credits);
        announce(source, Component.translatable("tablegames.house.reserve_set", format(credits)),
                Component.translatable("tablegames.house.reserve_set_by", source.getDisplayName(),
                        format(credits)));
        return status(source);
    }

    /**
     * Answers "why is my table limit so low" with a number.
     * <p>
     * Operators reach for a bigger maximum long before they reach for a
     * bigger bankroll, so the command that refuses the first should name the
     * second.
     */
    private static int plan(CommandSourceStack source, long desiredMaximumBet) {
        MinecraftServer server = source.getServer();
        HouseBankroll bankroll = OutcomeSettler.bankroll(server);
        long needed = bankroll.bankrollNeededFor(desiredMaximumBet, WORST_CASE_RATIO);

        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.plan",
                format(desiredMaximumBet), format(needed),
                format(bankroll.balance())), false);

        if (needed > bankroll.balance()) {
            source.sendSuccess(() -> Component.translatable(
                    "tablegames.house.plan_short",
                    format(needed - bankroll.balance())).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * Tells whoever changed the house what they did, and everyone else who
     * runs it that it happened.
     * <p>
     * Not vanilla's admin broadcast. That one reaches operators only — a
     * staff administrator who is not one would never hear that the bank they
     * look after had been emptied — and it repeats the actor's own message
     * back to them in brackets, "you added", to people who did not add
     * anything. So the others get a line of their own, naming who did it.
     * The server log gets the same line, so the change is on record even
     * with nobody online to see it.
     */
    private static void announce(CommandSourceStack source, Component toActor, Component toOthers) {
        source.sendSuccess(() -> toActor, false);
        MinecraftServer server = source.getServer();
        ServerPlayer actor = source.getPlayer();
        Component notice = toOthers.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != actor && HouseStaff.mayRunTheHouse(other.createCommandSourceStack())) {
                other.sendSystemMessage(notice);
            }
        }
        if (actor != null) {
            server.sendSystemMessage(toOthers);
        }
    }

    private static String format(long credits) {
        return CreditFormat.of(credits);
    }
}
