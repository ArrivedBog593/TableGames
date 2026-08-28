package com.github.arrivedbog593.tablegames.platform.command;

import com.github.arrivedbog593.tablegames.engine.economy.HouseBankroll;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Managing the casino's bankroll.
 * <p>
 * The bankroll starts empty and is seeded here. That is deliberate: a casino
 * that opens with invented funds is a casino that mints credits, and the
 * operator putting real credits behind the tables is what makes every payout
 * afterwards honest.
 */
public final class HouseCommands {

    /** Roulette's straight-up bet, quoted in the status report as the worst case. */
    private static final int WORST_CASE_RATIO = 35;

    private HouseCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tablegames")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("house")
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(context -> add(context.getSource(),
                                        LongArgumentType.getLong(context, "amount")))))
                .then(Commands.literal("take")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(context -> take(context.getSource(),
                                        LongArgumentType.getLong(context, "amount")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(context -> set(context.getSource(),
                                        LongArgumentType.getLong(context, "amount")))))
                .then(Commands.literal("exposure")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(1, 100))
                                .executes(context -> exposure(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "percent")))))
                .then(Commands.literal("reserve")
                        .then(Commands.argument("credits", LongArgumentType.longArg(0))
                                .executes(context -> reserve(context.getSource(),
                                        LongArgumentType.getLong(context, "credits")))))
                .then(Commands.literal("plan")
                        .then(Commands.argument("maxbet", LongArgumentType.longArg(1))
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
        HouseBankroll bankroll = CreditStorage.get(server).bankroll(server);

        ChatFormatting colour = switch (bankroll.status()) {
            case HEALTHY -> ChatFormatting.GREEN;
            case LOW -> ChatFormatting.YELLOW;
            case CLOSED -> ChatFormatting.RED;
        };

        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.balance", format(bankroll.balance())).withStyle(colour), false);

        if (!bankroll.isOpen()) {
            source.sendSuccess(() -> Component.translatable(
                            "tablegames.house.closed", format(bankroll.minimumReserve()))
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

        if (bankroll.status() == HouseBankroll.Status.LOW) {
            source.sendSuccess(() -> Component.translatable("tablegames.house.low")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int add(CommandSourceStack source, long amount) {
        CreditStorage storage = CreditStorage.get(source.getServer());
        storage.creditHouse(amount);
        EconomyEvents.recordHouse(storage, TransactionType.ADMIN_GIVE, amount,
                storage.houseBalance(), "seeded by " + source.getTextName());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.added", format(amount),
                format(storage.houseBalance())), true);
        return status(source);
    }

    private static int take(CommandSourceStack source, long amount) {
        CreditStorage storage = CreditStorage.get(source.getServer());
        if (!storage.debitHouse(amount)) {
            source.sendFailure(Component.translatable(
                    "tablegames.house.cannot_take", format(storage.houseBalance())));
            return 0;
        }
        EconomyEvents.recordHouse(storage, TransactionType.ADMIN_TAKE, -amount,
                storage.houseBalance(), "withdrawn by " + source.getTextName());
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.taken", format(amount),
                format(storage.houseBalance())), true);
        return status(source);
    }

    private static int set(CommandSourceStack source, long amount) {
        CreditStorage storage = CreditStorage.get(source.getServer());
        long before = storage.houseBalance();
        long delta = amount - before;
        if (delta > 0) {
            storage.creditHouse(delta);
        } else if (delta < 0) {
            storage.debitHouse(-delta);
        }
        if (delta != 0) {
            EconomyEvents.recordHouse(storage,
                    delta > 0 ? TransactionType.ADMIN_GIVE : TransactionType.ADMIN_TAKE,
                    delta, storage.houseBalance(), "set by " + source.getTextName());
        }
        return status(source);
    }

    private static int exposure(CommandSourceStack source, int percent) {
        EconomyData.get(source.getServer()).setExposurePercent(percent);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.exposure_set", percent), true);
        return status(source);
    }

    private static int reserve(CommandSourceStack source, long credits) {
        EconomyData.get(source.getServer()).setMinimumReserve(credits);
        source.sendSuccess(() -> Component.translatable(
                "tablegames.house.reserve_set", format(credits)), true);
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
        HouseBankroll bankroll = CreditStorage.get(server).bankroll(server);
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

    private static String format(long credits) {
        return String.format("%,d", credits);
    }
}