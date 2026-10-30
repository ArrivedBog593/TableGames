package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackFormat;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackHand;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackStage;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.NaturalPayout;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackActionPayload;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload.DealView;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload.FormatView;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload.HandView;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload.SeatHands;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload.SeatScore;
import com.github.arrivedbog593.tablegames.platform.network.PlayerFunds;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.network.TableActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A blackjack table: the dealer across the top, a row for every seat, and
 * underneath, only the buttons that mean something right now.
 * <p>
 * The bottom of the screen changes with the hand, so a player is never asked
 * to find the right control among ones that do nothing: chips while the table
 * takes wagers, insurance while the dealer shows an ace, and hit, stand,
 * double and split on their own turn — the last two lit only when the hand
 * and the stack allow them. Everybody else's turn shows the same buttons dark,
 * so nothing jumps around when the turn comes to you.
 * <p>
 * One screen for every format. In a tournament the felt adds each player's
 * chips and the count of hands, and a wager is made final with a button of
 * its own. With a player holding the bank, the dealer's row says whose bank
 * it is and the bank plays its cards with the same hit and stand.
 * <p>
 * Everyone at the table sees every hand. That is how blackjack is dealt, face
 * up, and it is what a spectator came to watch.
 */
public final class BlackjackScreen extends TableScreen {

    private static final long[] CHIPS = {1, 5, 25, 100, 500, 1000, 5000};

    private static final int PANEL_W = 332;
    private static final int PANEL_H = 264;

    /** The house rules, on a line of their own over the felt. */
    private static final int PLACARD_Y = 31;
    private static final int DEALER_Y = 42;
    private static final int DEALER_H = 24;
    private static final int ROWS_Y = 70;
    private static final int ROW_H = 21;
    private static final int CARDS_X = 92;
    /** Where a seat's hands must end. Past it, its cards close up. */
    private static final int CARDS_RIGHT = PANEL_W - 9;
    /** Kept clear at the end of each row for a seat's chips or score, where the table keeps any. */
    private static final int SCORE_W = 46;

    private static final int CONTROL_H = 16;
    private static final int ROW_A_Y = 224;
    private static final int ROW_B_Y = 243;
    private static final int CHIP_W = 26;
    private static final int MOVE_W = 74;
    private static final int WIDE_W = 100;
    /** Row B holds four buttons across the panel; these widths fit their longest labels. */
    private static final int BET_W = 84;
    private static final int REBUY_W = 66;
    private static final int LEAVE_W = 70;

    /** How long "withdraw" waits for the second click that confirms it. */
    private static final long WITHDRAW_ARMED_MILLIS = 3000;

    private static final int FELT = 0xFF1E5A3A;
    private static final int FELT_ON_CLOCK = 0xFF2A7048;
    private static final int GOLD = 0xFFFFD54F;
    /** "Your turn" on the grey header, where the felt's gold barely shows. */
    private static final int YOUR_TURN = 0xFFB06A00;
    private static final int WIN = 0xFF7ED27E;
    private static final int LOSS = 0xFFE07070;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int DARK = 0xFF4A4A4A;

    private BuyInPrompt prompt;

    /** When a first click on "withdraw" stops counting as one; zero when not armed. */
    private long withdrawArmedUntil;

    public BlackjackScreen(BlockPos tablePos, String gameId) {
        super(Component.translatable("tablegames.game." + gameId), tablePos, PANEL_W, PANEL_H);
        ClientBlackjackState.reset();
    }

    @Override
    protected void init() {
        super.init();
        if (prompt != null) {
            openPrompt(prompt.isRebuy());
        }
    }

    // --- The buy-in prompt, as at roulette --------------------------------------------------

    private void openPrompt(boolean rebuy) {
        closePrompt();
        prompt = new BuyInPrompt(font, rebuy, left + PANEL_W / 2, top + PANEL_H / 2,
                () -> ClientBlackjackState.state().funds(),
                amount -> {
                    sendSeatAction(rebuy ? TableActionPayload.KIND_REBUY : TableActionPayload.KIND_SIT, amount);
                    closePrompt();
                },
                this::closePrompt);
        addWidget(prompt.box());
        setFocused(prompt.box());
    }

    private void closePrompt() {
        if (prompt != null) {
            removeWidget(prompt.box());
            prompt = null;
            setFocused(null);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (prompt != null && prompt.isRebuy() != ClientBlackjackState.state().isSeated()) {
            closePrompt();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (prompt != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                prompt.cancel();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                prompt.confirm();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Drawing ----------------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        BlackjackStatePayload state = ClientBlackjackState.state();

        drawPanel(graphics, left, top, left + PANEL_W, top + PANEL_H);
        renderHeader(graphics, state);
        renderDealer(graphics, state);
        renderSeats(graphics, state);
        renderControls(graphics, mouseX, mouseY, state);
        if (prompt == null) {
            renderHover(graphics, mouseX, mouseY, state);
        } else {
            graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0x90000000);
            prompt.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderHeader(GuiGraphics graphics, BlackjackStatePayload state) {
        graphics.drawString(font, title, left + 8, top + 7, LABEL_TEXT, false);

        Component balance = headerFunds(state);
        graphics.drawString(font, balance, left + PANEL_W - 8 - font.width(balance), top + 7,
                0xFF2E7D32, false);

        Component status = status(state);
        graphics.drawString(font, status, left + (PANEL_W - font.width(status)) / 2, top + 20,
                statusColor(state), false);
    }

    /** Top right: what the viewer plays with here. */
    private static Component headerFunds(BlackjackStatePayload state) {
        FormatView format = state.format();
        Optional<SeatScore> mine = state.myScore();
        if (state.blackjackFormat() == BlackjackFormat.TOURNAMENT && mine.isPresent()) {
            return Component.translatable("tablegames.blackjack.tourney_chips", format(mine.get().chips()));
        }
        if (state.isSeated() && !format.forMoney()) {
            return mine.map(score -> Component.translatable("tablegames.blackjack.score", signed(score.chips())))
                    .orElse(Component.translatable("tablegames.blackjack.for_fun"));
        }
        PlayerFunds funds = ClientBlackjackState.funds();
        return state.isSeated()
                ? Component.translatable("tablegames.roulette.stack", format(funds.stack()))
                : Component.translatable("tablegames.roulette.balance", format(funds.balance()));
    }

    /** One line saying what the table is waiting for. */
    private Component status(BlackjackStatePayload state) {
        DealView deal = state.deal();
        BlackjackFormat format = state.blackjackFormat();
        int seconds = state.table().secondsLeft();
        if (format == BlackjackFormat.TOURNAMENT) {
            Component tourney = tournamentStatus(state);
            if (tourney != null) {
                return tourney;
            }
        }
        if (!deal.hasHand()) {
            if (format == BlackjackFormat.BANKER) {
                return bankWaitingStatus(state);
            }
            if (state.phase().isCountingDown()) {
                return Component.translatable("tablegames.blackjack.dealing_in", seconds);
            }
            return Component.translatable("tablegames.roulette.place_your_bets");
        }
        if (ClientBlackjackState.dealing()) {
            return Component.translatable("tablegames.blackjack.dealing");
        }
        boolean bank = format == BlackjackFormat.BANKER;
        return switch (stageOf(deal)) {
            case INSURANCE -> Component.translatable(bank ? "tablegames.blackjack.bank_insurance_open"
                    : "tablegames.blackjack.insurance_open", seconds);
            case PLAYERS -> onClockIsMe(state)
                    ? Component.translatable("tablegames.blackjack.your_turn", seconds)
                    : Component.translatable("tablegames.blackjack.their_turn", nameOnClock(state), seconds);
            case DEALER -> bank ? bankPlayingStatus(state, seconds)
                    : Component.translatable("tablegames.blackjack.dealer_plays");
            case SETTLED -> dealerVerdict(deal, bank);
        };
    }

    /** The lobby, the betting between hands, and the final standings; null during a hand. */
    private static Component tournamentStatus(BlackjackStatePayload state) {
        BlackjackStatePayload.TourneyView tourney = state.format().tourney();
        int seconds = state.table().secondsLeft();
        switch (tourney.state()) {
            case BlackjackStatePayload.TOURNEY_LOBBY -> {
                return state.table().seats().size() < 2
                        ? Component.translatable("tablegames.blackjack.need_players")
                        : Component.translatable("tablegames.blackjack.tourney_ready_up");
            }
            case BlackjackStatePayload.TOURNEY_OVER -> {
                List<String> winners = new ArrayList<>();
                for (SeatScore score : state.format().scores()) {
                    if (score.place() == 1) {
                        winners.add(nameAt(state, score.seatIndex()));
                    }
                }
                return winners.size() == 1
                        ? Component.translatable("tablegames.blackjack.tourney_won", winners.getFirst())
                        : Component.translatable("tablegames.blackjack.tourney_tied", String.join(", ", winners));
            }
            default -> {
                if (state.deal().hasHand()) {
                    return null;
                }
                boolean done = state.myScore().map(score -> score.has(BlackjackStatePayload.CONFIRMED))
                        .orElse(false);
                return done
                        ? Component.translatable("tablegames.blackjack.tourney_waiting", seconds)
                        : Component.translatable("tablegames.blackjack.tourney_bet", seconds);
            }
        }
    }

    private static Component bankWaitingStatus(BlackjackStatePayload state) {
        if (state.table().seats().size() < 2) {
            return Component.translatable("tablegames.blackjack.need_players");
        }
        int bankerSeat = state.format().bankerSeat();
        if (!state.phase().isCountingDown() || bankerSeat == BlackjackStatePayload.NOBODY) {
            return Component.translatable("tablegames.blackjack.bank_waiting");
        }
        return Component.translatable("tablegames.blackjack.bank_next_hand",
                nameAt(state, bankerSeat), state.table().secondsLeft());
    }

    private static Component bankPlayingStatus(BlackjackStatePayload state, int seconds) {
        if (state.iAmBanker() && state.mine().may(BlackjackStatePayload.HIT)) {
            return Component.translatable("tablegames.blackjack.bank_your_turn", seconds);
        }
        boolean byHand = state.format().has(BlackjackStatePayload.BANK_BY_HAND);
        return Component.translatable(byHand && seconds > 0 ? "tablegames.blackjack.bank_deciding"
                : "tablegames.blackjack.bank_plays", nameAt(state, state.format().bankerSeat()));
    }

    private static int statusColor(BlackjackStatePayload state) {
        if (ClientBlackjackState.dealing() || !state.deal().hasHand()) {
            return LABEL_TEXT;
        }
        BlackjackStage stage = stageOf(state.deal());
        if (stage == BlackjackStage.PLAYERS && onClockIsMe(state)) {
            return YOUR_TURN;
        }
        if (stage == BlackjackStage.DEALER && state.iAmBanker() && state.mine().may(BlackjackStatePayload.HIT)) {
            return YOUR_TURN;
        }
        return LABEL_TEXT;
    }

    private static Component dealerVerdict(DealView deal, boolean bank) {
        String who = bank ? "bank" : "dealer";
        List<Card> dealer = BlackjackStatePayload.cards(deal.dealer());
        if (BlackjackHand.isNatural(dealer)) {
            return Component.translatable("tablegames.blackjack." + who + "_blackjack");
        }
        int total = BlackjackHand.totalOf(dealer);
        return total > 21
                ? Component.translatable("tablegames.blackjack." + who + "_busts")
                : Component.translatable("tablegames.blackjack." + who + "_has", total);
    }

    private void renderDealer(GuiGraphics graphics, BlackjackStatePayload state) {
        int x = left + 7;
        int y = top + DEALER_Y;
        graphics.fill(x, y, left + PANEL_W - 7, y + DEALER_H, FELT);
        int bankerSeat = state.format().bankerSeat();
        if (state.blackjackFormat() == BlackjackFormat.BANKER && bankerSeat != BlackjackStatePayload.NOBODY) {
            // Whose bank it is, or whose it will be, on two lines: a name is
            // longer than the column is wide.
            graphics.drawString(font, Component.translatable(state.deal().hasHand()
                    ? "tablegames.blackjack.bank" : "tablegames.blackjack.bank_next"), x + 4, y + 3, GOLD, false);
            graphics.drawString(font, font.plainSubstrByWidth(nameAt(state, bankerSeat), CARDS_X - 15),
                    x + 4, y + 13, 0xFFE8E8E8, false);
        } else {
            graphics.drawString(font, Component.translatable("tablegames.blackjack.dealer"),
                    x + 4, y + 8, 0xFFE8E8E8, false);
        }

        DealView deal = state.deal();
        List<Card> shown = new ArrayList<>();
        int cardX = left + CARDS_X;
        List<Card> dealer = BlackjackStatePayload.cards(deal.dealer());
        for (int i = 0; i < dealer.size(); i++) {
            if (!ClientBlackjackState.dealerLanded(i)) {
                continue;
            }
            PlayingCards.face(graphics, font, cardX + i * (PlayingCards.WIDTH + 2), y + 3, dealer.get(i));
            shown.add(dealer.get(i));
        }
        if (deal.holeHidden() && ClientBlackjackState.dealerLanded(1)) {
            PlayingCards.back(graphics, cardX + PlayingCards.WIDTH + 2, y + 3);
        }
        int dealerEnd = cardX;
        if (!shown.isEmpty()) {
            int count = Math.max(shown.size(), deal.holeHidden() ? 2 : 0);
            // Only what is face up. Calling an ace alone "soft 11" would read
            // as a statement about the hole card.
            Component total = deal.holeHidden()
                    ? Component.literal(String.valueOf(BlackjackHand.totalOf(shown)))
                    : totalLabel(shown, false);
            int totalX = cardX + count * (PlayingCards.WIDTH + 2) + 4;
            graphics.drawString(font, total, totalX, y + 8, 0xFFFFFFFF, false);
            dealerEnd = totalX + font.width(total);
        }

        Component placard = placard(state);
        graphics.drawString(font, placard, left + (PANEL_W - font.width(placard)) / 2, top + PLACARD_Y,
                0xFF3F5F4A, false);
        // How deep into the shoe the table is sits by the dealer, where the
        // shoe would be; counting is legal here, as at any real table.
        BlackjackStatePayload.RulesView rules = deal.rules();
        if (rules.shoeSize() > 0) {
            Component shoe = Component.translatable("tablegames.blackjack.shoe", rules.shoeLeft());
            int shoeX = left + PANEL_W - 11 - font.width(shoe);
            // A dealer who drew a long hand covers the count rather than collide with it.
            if (shoeX > dealerEnd + 6) {
                graphics.drawString(font, shoe, shoeX, y + 8, 0xFF8AB09A, false);
            }
        }
    }

    /** The line over the felt: what the table chose, and what it is played for. */
    private static Component placard(BlackjackStatePayload state) {
        BlackjackStatePayload.RulesView rules = state.deal().rules();
        NaturalPayout payout = NaturalPayout.values()[Math.clamp(rules.naturalPayout(), 0,
                NaturalPayout.values().length - 1)];
        Component pays = Component.translatable("tablegames.setting.blackjack.natural_pays." + payout.optionName());
        FormatView format = state.format();
        return switch (state.blackjackFormat()) {
            case HOUSE -> Component.translatable("tablegames.blackjack.placard", pays, rules.decks(),
                    Component.translatable(rules.hitsSoft17() ? "tablegames.blackjack.h17"
                            : "tablegames.blackjack.s17"));
            case TOURNAMENT -> {
                BlackjackStatePayload.TourneyView tourney = format.tourney();
                Component stake = !format.forMoney()
                        ? Component.translatable("tablegames.blackjack.for_fun")
                        : tourney.state() == BlackjackStatePayload.TOURNEY_LOBBY
                        ? Component.translatable("tablegames.blackjack.entry", format(tourney.entry()))
                        : Component.translatable("tablegames.blackjack.pot", format(tourney.pot()));
                Component progress = tourney.state() == BlackjackStatePayload.TOURNEY_LOBBY
                        ? Component.translatable("tablegames.blackjack.hands_long", tourney.hands())
                        : Component.translatable("tablegames.blackjack.hand_of", tourney.handNumber(),
                        tourney.hands());
                yield Component.translatable("tablegames.blackjack.tourney_placard", progress, stake, pays,
                        rules.decks());
            }
            case BANKER -> Component.translatable("tablegames.blackjack.bank_placard",
                    Component.translatable(format.forMoney() ? "tablegames.blackjack.bank_stake"
                            : "tablegames.blackjack.bank_stake_fun", format(state.mine().tableMinimum())),
                    pays,
                    Component.translatable(format.has(BlackjackStatePayload.BANK_WINS_TIES)
                            ? "tablegames.blackjack.ties_to_bank" : "tablegames.blackjack.ties_push"));
        };
    }

    private void renderSeats(GuiGraphics graphics, BlackjackStatePayload state) {
        DealView deal = state.deal();
        FormatView format = state.format();
        List<RouletteStatePayload.SeatView> seats = state.table().seats();
        boolean scored = !format.scores().isEmpty();
        int x = left + 7;
        int width = PANEL_W - 14;
        for (int index = 0; index < state.table().maxSeats(); index++) {
            int y = top + ROWS_Y + index * ROW_H;
            boolean onClock = deal.hasHand() && deal.onClockSeat() == index && !ClientBlackjackState.dealing();
            graphics.fill(x, y, x + width, y + ROW_H - 1, onClock ? FELT_ON_CLOCK : FELT);
            if (index >= seats.size()) {
                continue;
            }
            RouletteStatePayload.SeatView seat = seats.get(index);
            boolean me = index == state.mySeat();
            Optional<SeatScore> score = format.score(index);
            boolean out = score.map(s -> s.has(BlackjackStatePayload.OUT)).orElse(false);
            String prefix = score.filter(s -> s.place() > 0).map(s -> s.place() + ". ").orElse("");
            if (prefix.isEmpty() && !deal.hasHand() && seat.ready()) {
                prefix = "✓ ";
            }
            graphics.drawString(font, font.plainSubstrByWidth(prefix + seat.name(), CARDS_X - 15),
                    x + 4, y + 2, out ? MUTED : me ? GOLD : 0xFFE8E8E8, false);
            score.ifPresent(s -> renderScore(graphics, state, s, x + width - 4, y));
            if (index == format.bankerSeat() && state.blackjackFormat() == BlackjackFormat.BANKER) {
                Component tag = Component.translatable("tablegames.blackjack.bank_tag");
                int tagX = (scored ? x + width - 4 - SCORE_W : x + width - 4) - font.width(tag);
                graphics.drawString(font, tag, tagX, y + 6, GOLD, false);
            }

            Optional<SeatHands> spot = deal.seat(index);
            if (spot.isEmpty()) {
                renderSeatNote(graphics, state, score, x + 4, y + 11);
                continue;
            }
            SeatHands hands = spot.get();
            if (!deal.hasHand()) {
                boolean confirmed = score.map(s -> s.has(BlackjackStatePayload.CONFIRMED)).orElse(false);
                graphics.drawString(font, format(hands.openingBet()) + (confirmed ? " ✓" : ""),
                        x + 4, y + 11, 0xFFE0B33A, false);
                continue;
            }
            long staked = hands.hands().stream().mapToLong(HandView::bet).sum() + hands.insurance();
            Component under = hands.hasResult() ? resultLabel(hands.result())
                    : Component.literal(format(staked));
            graphics.drawString(font, under, x + 4, y + 11,
                    hands.hasResult() ? resultColor(hands.result()) : 0xFFE0B33A, false);
            renderHands(graphics, hands, onClock, y, scored ? CARDS_RIGHT - SCORE_W : CARDS_RIGHT);
        }
    }

    /** At the end of the row: tournament chips, or the running score of a game for fun. */
    private void renderScore(GuiGraphics graphics, BlackjackStatePayload state, SeatScore score, int right, int y) {
        String text;
        int color;
        if (state.blackjackFormat() == BlackjackFormat.TOURNAMENT) {
            text = format(score.chips());
            color = score.has(BlackjackStatePayload.OUT) ? MUTED : 0xFFE0B33A;
        } else {
            text = signed(score.chips());
            color = resultColor(score.chips());
        }
        graphics.drawString(font, text, right - font.width(text), y + 6, color, false);
    }

    /** Under the name, with nothing on the felt: out of the tournament, or what it paid. */
    private void renderSeatNote(GuiGraphics graphics, BlackjackStatePayload state, Optional<SeatScore> score,
                                int x, int y) {
        if (score.isEmpty()) {
            return;
        }
        SeatScore s = score.get();
        BlackjackStatePayload.TourneyView tourney = state.format().tourney();
        if (tourney.state() == BlackjackStatePayload.TOURNEY_OVER && state.format().forMoney()) {
            long net = s.prize() - tourney.entry();
            graphics.drawString(font, resultLabel(net), x, y, resultColor(net), false);
        } else if (s.has(BlackjackStatePayload.WITHDRAWN)) {
            graphics.drawString(font, Component.translatable("tablegames.blackjack.withdrawn"), x, y, MUTED, false);
        } else if (s.has(BlackjackStatePayload.OUT)) {
            graphics.drawString(font, Component.translatable("tablegames.blackjack.out"), x, y, MUTED, false);
        }
    }

    private void renderHands(GuiGraphics graphics, SeatHands seat, boolean onClock, int rowY, int right) {
        List<HandView> hands = seat.hands();
        List<List<Card>> landed = new ArrayList<>();
        int cardCount = 0;
        int fixed = 0;
        for (int h = 0; h < hands.size(); h++) {
            List<Card> cards = hands.get(h).asCards();
            List<Card> down = new ArrayList<>();
            for (int c = 0; c < cards.size(); c++) {
                if (ClientBlackjackState.landed(seat.seatIndex(), h, c)) {
                    down.add(cards.get(c));
                }
            }
            landed.add(down);
            if (!down.isEmpty()) {
                cardCount += down.size() - 1;
                fixed += PlayingCards.WIDTH + 3 + font.width(totalLabel(down, hands.size() > 1)) + 6;
            }
        }
        // Four split hands do not fit at the usual spacing. The cards close up
        // until they do, down to a sliver that still shows the rank.
        int room = right - CARDS_X - fixed;
        int step = cardCount == 0 ? PlayingCards.STEP
                : Math.clamp(room / cardCount, 7, PlayingCards.STEP);

        int cardX = left + CARDS_X;
        for (int h = 0; h < hands.size(); h++) {
            HandView hand = hands.get(h);
            List<Card> down = landed.get(h);
            if (down.isEmpty()) {
                continue;
            }
            int startX = cardX;
            for (Card card : down) {
                PlayingCards.face(graphics, font, cardX, rowY, card);
                cardX += step;
            }
            int endX = cardX - step + PlayingCards.WIDTH;
            if (onClock && h == seat.handOnClock() && hands.size() > 1) {
                graphics.renderOutline(startX - 1, rowY - 1, endX - startX + 2, PlayingCards.HEIGHT + 1, GOLD);
            }
            Component total = totalLabel(down, hands.size() > 1);
            graphics.drawString(font, total, endX + 3, rowY + 6,
                    BlackjackHand.totalOf(down) > 21 ? LOSS : 0xFFFFFFFF, false);
            if (hand.doubled()) {
                graphics.drawString(font, "×2", endX + 3, rowY - 2 + PlayingCards.HEIGHT - 8, GOLD, false);
            }
            cardX = endX + 3 + font.width(total) + 6;
        }
    }

    /** "17", "soft 17", "Blackjack" or "Bust", for what has landed. */
    private static Component totalLabel(List<Card> cards, boolean afterSplit) {
        int total = BlackjackHand.totalOf(cards);
        if (!afterSplit && BlackjackHand.isNatural(cards)) {
            return Component.translatable("tablegames.blackjack.blackjack");
        }
        if (total > 21) {
            return Component.translatable("tablegames.blackjack.bust");
        }
        if (BlackjackHand.isSoft(cards) && total < 21) {
            return Component.translatable("tablegames.blackjack.soft", total);
        }
        return Component.literal(String.valueOf(total));
    }

    private static Component resultLabel(long delta) {
        if (delta == 0) {
            return Component.translatable("tablegames.blackjack.push");
        }
        return Component.literal(signed(delta));
    }

    private static String signed(long amount) {
        return amount > 0 ? "+" + format(amount) : amount < 0 ? "-" + format(-amount) : "0";
    }

    private static int resultColor(long delta) {
        return delta > 0 ? WIN : delta < 0 ? LOSS : MUTED;
    }

    // --- Controls ---------------------------------------------------------------------------

    /** What can be clicked on the bottom two rows, and where. */
    private enum Control {
        HIT, STAND, DOUBLE, SPLIT, INSURE, DECLINE, CLEAR, REPEAT, READY, CONFIRM, WITHDRAW, SIT, REBUY, LEAVE
    }

    private record Button(Control control, int x, int y, int w, Component label, int face, boolean live) {
    }

    private List<Button> buttons(BlackjackStatePayload state) {
        List<Button> buttons = new ArrayList<>();
        int rowA = top + ROW_A_Y;
        int rowB = top + ROW_B_Y;
        int x0 = left + 8;
        DealView deal = state.deal();
        BlackjackStatePayload.MineView mine = state.mine();

        if (!state.isSeated()) {
            boolean free = state.table().hasFreeSeat();
            buttons.add(new Button(Control.SIT, left + (PANEL_W - WIDE_W) / 2, rowB, WIDE_W,
                    Component.translatable(free ? "tablegames.table.sit_down" : "tablegames.table.full_short"),
                    free ? 0xFF2E7D32 : DARK, free));
            return buttons;
        }

        if (deal.hasHand() && stageOf(deal) == BlackjackStage.INSURANCE && !ClientBlackjackState.dealing()) {
            boolean open = mine.may(BlackjackStatePayload.INSURE);
            buttons.add(new Button(Control.INSURE, x0, rowA, WIDE_W,
                    Component.translatable("tablegames.blackjack.insure", format(mine.insuranceCost())),
                    open ? 0xFF3A5B8A : DARK, open));
            buttons.add(new Button(Control.DECLINE, x0 + WIDE_W + 4, rowA, WIDE_W,
                    Component.translatable("tablegames.blackjack.no_insurance"), open ? 0xFF6A4A2A : DARK, open));
        } else if (deal.hasHand()) {
            Control[] moves = {Control.HIT, Control.STAND, Control.DOUBLE, Control.SPLIT};
            int[] bits = {BlackjackStatePayload.HIT, BlackjackStatePayload.STAND,
                    BlackjackStatePayload.DOUBLE, BlackjackStatePayload.SPLIT};
            int[] faces = {0xFF2E7D32, 0xFF8A3A3A, 0xFF8A4B1E, 0xFF3A5B8A};
            String[] keys = {"hit", "stand", "double", "split"};
            for (int i = 0; i < moves.length; i++) {
                boolean live = mine.may(bits[i]);
                buttons.add(new Button(moves[i], x0 + i * (MOVE_W + 4), rowA, MOVE_W,
                        Component.translatable("tablegames.blackjack." + keys[i]), live ? faces[i] : DARK, live));
            }
        }

        switch (state.blackjackFormat()) {
            case HOUSE -> houseButtons(buttons, state, x0, rowB);
            case TOURNAMENT -> tournamentButtons(buttons, state, x0, rowB);
            case BANKER -> bankerButtons(buttons, state, x0, rowB);
        }
        return buttons;
    }

    private void houseButtons(List<Button> buttons, BlackjackStatePayload state, int x0, int rowB) {
        boolean betting = state.bettingOpen();
        long myBet = state.mySpot().map(SeatHands::openingBet).orElse(0L);
        long last = ClientBlackjackState.lastBet(BlackjackFormat.HOUSE);
        if (betting && myBet == 0 && last > 0) {
            boolean live = last <= state.funds().stack();
            buttons.add(new Button(Control.REPEAT, x0, rowB, BET_W,
                    Component.translatable("tablegames.blackjack.repeat", format(last)),
                    live ? 0xFF3A5B8A : DARK, live));
        } else {
            boolean live = betting && myBet > 0;
            buttons.add(new Button(Control.CLEAR, x0, rowB, BET_W,
                    Component.translatable("tablegames.roulette.clear"), live ? 0xFF8A3A3A : DARK, live));
        }
        boolean ready = amIReady(state);
        buttons.add(new Button(Control.READY, x0 + BET_W + 4, rowB, BET_W,
                Component.translatable(!betting ? "tablegames.blackjack.in_play"
                        : ready ? "tablegames.table.not_ready" : "tablegames.table.ready"),
                !betting ? DARK : ready ? 0xFFB8860B : 0xFF2E7D32, betting && myBet > 0));
        seatButtons(buttons, state, rowB);
    }

    /**
     * In the lobby, ready and the seat's own buttons. Once it starts, the
     * wager — cleared, or made final — and withdrawing, which takes two
     * clicks because it forfeits the entry.
     */
    private void tournamentButtons(List<Button> buttons, BlackjackStatePayload state, int x0, int rowB) {
        int stateOfIt = state.format().tourney().state();
        Optional<SeatScore> mine = state.myScore();
        if (stateOfIt != BlackjackStatePayload.TOURNEY_LIVE || mine.isEmpty()) {
            if (stateOfIt == BlackjackStatePayload.TOURNEY_LOBBY) {
                boolean ready = amIReady(state);
                boolean entered = !state.format().forMoney()
                        || state.funds().stack() >= state.format().tourney().entry();
                buttons.add(new Button(Control.READY, x0, rowB, BET_W,
                        Component.translatable(ready ? "tablegames.table.not_ready" : "tablegames.table.ready"),
                        !entered ? DARK : ready ? 0xFFB8860B : 0xFF2E7D32, entered));
            }
            seatButtons(buttons, state, rowB);
            return;
        }
        boolean betting = state.bettingOpen() && !state.deal().hasHand();
        boolean confirmed = mine.get().has(BlackjackStatePayload.CONFIRMED);
        long myBet = state.mySpot().map(SeatHands::openingBet).orElse(0L);
        boolean in = !mine.get().has(BlackjackStatePayload.OUT);
        long last = ClientBlackjackState.lastBet(BlackjackFormat.TOURNAMENT);
        if (betting && myBet == 0 && last > 0) {
            // Only as much as the player still has: a repeat can go all in,
            // never past it.
            boolean live = last <= state.mine().tableMaximum();
            buttons.add(new Button(Control.REPEAT, x0, rowB, BET_W,
                    Component.translatable("tablegames.blackjack.repeat", format(last)),
                    live ? 0xFF3A5B8A : DARK, live));
        } else {
            boolean canClear = betting && myBet > 0;
            buttons.add(new Button(Control.CLEAR, x0, rowB, BET_W,
                    Component.translatable("tablegames.roulette.clear"), canClear ? 0xFF8A3A3A : DARK, canClear));
        }
        boolean canConfirm = betting && myBet > 0 && !confirmed;
        buttons.add(new Button(Control.CONFIRM, x0 + BET_W + 4, rowB, BET_W,
                Component.translatable(state.deal().hasHand() ? "tablegames.blackjack.in_play"
                        : confirmed ? "tablegames.blackjack.bet_placed" : "tablegames.blackjack.place_bet"),
                canConfirm ? 0xFF2E7D32 : DARK, canConfirm));
        boolean armed = System.currentTimeMillis() < withdrawArmedUntil;
        int width = REBUY_W + 4 + LEAVE_W;
        buttons.add(new Button(Control.WITHDRAW, left + PANEL_W - 8 - width, rowB, width,
                Component.translatable(armed ? "tablegames.blackjack.withdraw_sure" : "tablegames.blackjack.withdraw"),
                !in ? DARK : armed ? 0xFFB03030 : 0xFF6A4A2A, in));
    }

    private void bankerButtons(List<Button> buttons, BlackjackStatePayload state, int x0, int rowB) {
        boolean ready = amIReady(state);
        boolean open = !state.deal().hasHand() && state.phase().acceptsBets();
        buttons.add(new Button(Control.READY, x0, rowB, BET_W,
                Component.translatable(!open ? "tablegames.blackjack.in_play"
                        : ready ? "tablegames.table.not_ready" : "tablegames.table.ready"),
                !open ? DARK : ready ? 0xFFB8860B : 0xFF2E7D32, open));
        seatButtons(buttons, state, rowB);
    }

    /** Leaving the seat, and topping up where there is a stack: only between rounds. */
    private void seatButtons(List<Button> buttons, BlackjackStatePayload state, int rowB) {
        boolean between = !state.phase().isCountingDown();
        int sideX = left + PANEL_W - 8 - LEAVE_W;
        buttons.add(new Button(Control.LEAVE, sideX, rowB, LEAVE_W,
                Component.translatable("tablegames.table.stand_up"), between ? 0xFF6A4A2A : DARK, between));
        if (state.funds().hasBuyIn()) {
            buttons.add(new Button(Control.REBUY, sideX - REBUY_W - 4, rowB, REBUY_W,
                    Component.translatable("tablegames.table.rebuy"), between ? 0xFF2E7D32 : DARK, between));
        }
    }

    private void renderControls(GuiGraphics graphics, int mouseX, int mouseY, BlackjackStatePayload state) {
        if (showsChips(state)) {
            long room = remaining(state);
            for (int i = 0; i < CHIPS.length; i++) {
                int x = chipX(i);
                int y = top + ROW_A_Y;
                boolean usable = room >= CHIPS[i];
                drawButton(graphics, x, y, CHIP_W, CONTROL_H, 0xFF9A9A9A,
                        usable && isOver(mouseX, mouseY, x, y, CHIP_W, CONTROL_H));
                String label = CHIPS[i] >= 1000 ? (CHIPS[i] / 1000) + "k" : String.valueOf(CHIPS[i]);
                graphics.drawString(font, label, x + (CHIP_W - font.width(label)) / 2, y + 4,
                        usable ? 0xFFFFFFFF : 0xFF7A5050, false);
            }
            Component limits = Component.translatable("tablegames.blackjack.limits",
                    format(state.mine().tableMinimum()), format(state.mine().tableMaximum()));
            graphics.drawString(font, limits, left + PANEL_W - 8 - font.width(limits), top + ROW_A_Y + 4,
                    MUTED, false);
        }
        for (Button button : buttons(state)) {
            boolean hovered = button.live() && isOver(mouseX, mouseY, button.x(), button.y(), button.w(), CONTROL_H);
            drawButton(graphics, button.x(), button.y(), button.w(), CONTROL_H, button.face(), hovered);
            graphics.drawString(font, button.label(), button.x() + (button.w() - font.width(button.label())) / 2,
                    button.y() + 4, button.live() ? 0xFFFFFFFF : MUTED, false);
        }
    }

    /** Whether the chip row is up: the viewer can put a wager down right now. */
    private static boolean showsChips(BlackjackStatePayload state) {
        if (!state.isSeated() || !state.bettingOpen()) {
            return false;
        }
        return state.blackjackFormat() != BlackjackFormat.TOURNAMENT
                || !state.myScore().map(score -> score.has(BlackjackStatePayload.CONFIRMED)).orElse(true);
    }

    private void renderHover(GuiGraphics graphics, int mouseX, int mouseY, BlackjackStatePayload state) {
        for (Button button : buttons(state)) {
            if (!isOver(mouseX, mouseY, button.x(), button.y(), button.w(), CONTROL_H)) {
                continue;
            }
            String key = switch (button.control()) {
                case DOUBLE -> "tablegames.blackjack.double_hint";
                case SPLIT -> "tablegames.blackjack.split_hint";
                case INSURE -> "tablegames.blackjack.insure_hint";
                case WITHDRAW -> state.format().forMoney()
                        ? "tablegames.blackjack.withdraw_hint" : "tablegames.blackjack.withdraw_hint_fun";
                case CONFIRM -> "tablegames.blackjack.place_bet_hint";
                case REBUY, LEAVE -> button.live() ? null : "tablegames.buyin.between_rounds";
                default -> null;
            };
            if (key != null) {
                graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
            }
            return;
        }
    }

    private int chipX(int index) {
        return left + 8 + index * (CHIP_W + 2);
    }

    /** What the viewer may still add to their opening wager. */
    private static long remaining(BlackjackStatePayload state) {
        long myBet = state.mySpot().map(SeatHands::openingBet).orElse(0L);
        if (state.blackjackFormat() == BlackjackFormat.TOURNAMENT) {
            return Math.max(0L, state.mine().tableMaximum() - myBet);
        }
        return Math.max(0L, state.funds().stack() - myBet);
    }

    // --- Clicking ---------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (prompt != null) {
            if (prompt.mouseClicked(mx, my)) {
                click();
                return true;
            }
            if (prompt.contains(mx, my)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }

        BlackjackStatePayload state = ClientBlackjackState.state();
        if (showsChips(state)) {
            for (int i = 0; i < CHIPS.length; i++) {
                if (isOver(mx, my, chipX(i), top + ROW_A_Y, CHIP_W, CONTROL_H)) {
                    if (remaining(state) >= CHIPS[i]) {
                        send(BlackjackActionPayload.bet(tablePos, CHIPS[i]));
                    }
                    return true;
                }
            }
        }
        for (Button control : buttons(state)) {
            if (!isOver(mx, my, control.x(), control.y(), control.w(), CONTROL_H)) {
                continue;
            }
            if (control.live()) {
                press(control.control(), state);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void press(Control control, BlackjackStatePayload state) {
        switch (control) {
            case HIT -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_HIT, tablePos));
            case STAND -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_STAND, tablePos));
            case DOUBLE -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_DOUBLE, tablePos));
            case SPLIT -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_SPLIT, tablePos));
            case INSURE -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_INSURE, tablePos));
            case DECLINE -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_DECLINE_INSURANCE, tablePos));
            case CLEAR -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_CLEAR, tablePos));
            case REPEAT -> send(BlackjackActionPayload.bet(tablePos,
                    ClientBlackjackState.lastBet(state.blackjackFormat())));
            case CONFIRM -> send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_CONFIRM_BET, tablePos));
            case WITHDRAW -> {
                if (System.currentTimeMillis() < withdrawArmedUntil) {
                    withdrawArmedUntil = 0;
                    send(BlackjackActionPayload.of(BlackjackActionPayload.KIND_WITHDRAW, tablePos));
                } else {
                    withdrawArmedUntil = System.currentTimeMillis() + WITHDRAW_ARMED_MILLIS;
                    click();
                }
            }
            case READY -> sendSeatAction(amIReady(state)
                    ? TableActionPayload.KIND_NOT_READY : TableActionPayload.KIND_READY);
            case SIT -> {
                // A game played for fun asks nothing to sit down.
                if (state.funds().hasBuyIn()) {
                    click();
                    openPrompt(false);
                } else {
                    sendSeatAction(TableActionPayload.KIND_SIT, 0);
                }
            }
            case REBUY -> {
                click();
                openPrompt(true);
            }
            case LEAVE -> sendSeatAction(TableActionPayload.KIND_STAND);
        }
    }

    // --- Helpers ----------------------------------------------------------------------------

    private static BlackjackStage stageOf(DealView deal) {
        BlackjackStage[] all = BlackjackStage.values();
        return all[Math.clamp(deal.stage(), 0, all.length - 1)];
    }

    private static boolean onClockIsMe(BlackjackStatePayload state) {
        return state.isSeated() && state.deal().onClockSeat() == state.mySeat();
    }

    private static String nameOnClock(BlackjackStatePayload state) {
        return nameAt(state, state.deal().onClockSeat());
    }

    private static String nameAt(BlackjackStatePayload state, int seat) {
        List<RouletteStatePayload.SeatView> seats = state.table().seats();
        return seat >= 0 && seat < seats.size() ? seats.get(seat).name() : "?";
    }

    private static boolean amIReady(BlackjackStatePayload state) {
        int seat = state.mySeat();
        List<RouletteStatePayload.SeatView> seats = state.table().seats();
        return seat >= 0 && seat < seats.size() && seats.get(seat).ready();
    }

    private void sendSeatAction(int kind) {
        sendSeatAction(kind, 0);
    }

    private void sendSeatAction(int kind, long amount) {
        PacketDistributor.sendToServer(new TableActionPayload(kind, tablePos, amount));
        click();
    }

    private void send(BlackjackActionPayload payload) {
        PacketDistributor.sendToServer(payload);
        click();
    }

    private void click() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
