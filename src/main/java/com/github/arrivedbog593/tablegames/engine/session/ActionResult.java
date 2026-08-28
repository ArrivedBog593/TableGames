package com.github.arrivedbog593.tablegames.engine.session;

import java.util.Objects;

/**
 * The outcome of submitting an action.
 * <p>
 * Rejections are returned, never thrown. A player clicking an illegal button
 * is ordinary traffic, not an exceptional condition, and on a public server
 * it will happen constantly through lag and desync. Exceptions here would
 * mean a stack trace per misclick.
 *
 * @param accepted   whether the action was applied
 * @param messageKey translation key explaining a rejection, null when accepted
 */
public record ActionResult(boolean accepted, String messageKey) {

    private static final ActionResult OK = new ActionResult(true, null);

    public static ActionResult ok() {
        return OK;
    }

    public static ActionResult rejected(String messageKey) {
        return new ActionResult(false, Objects.requireNonNull(messageKey, "messageKey"));
    }

    /** Rejection reasons the session machinery raises on every game's behalf. */
    public static ActionResult notYourTurn() {
        return rejected("tablegames.reject.not_your_turn");
    }

    public static ActionResult notSeated() {
        return rejected("tablegames.reject.not_seated");
    }

    public static ActionResult wrongState() {
        return rejected("tablegames.reject.wrong_state");
    }

    public static ActionResult illegalAction() {
        return rejected("tablegames.reject.illegal_action");
    }

    public static ActionResult insufficientCredits() {
        return rejected("tablegames.reject.insufficient_credits");
    }
}