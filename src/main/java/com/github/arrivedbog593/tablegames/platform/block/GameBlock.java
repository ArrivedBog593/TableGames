package com.github.arrivedbog593.tablegames.platform.block;

/**
 * A block people play a game at.
 * <p>
 * Carries nothing. It exists so that "is there still something to play at
 * this position" can be asked without listing every such block at each place
 * that asks — and there is one place that asks, on the client, every tick a
 * game screen is open. Listing them there meant a screen that closed itself
 * the instant it opened on the second kind of block, which is exactly the
 * sort of thing a marker prevents from being possible.
 */
public interface GameBlock {
}
