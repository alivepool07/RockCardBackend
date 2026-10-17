package com.cardgame.model;

/**
 * Represents the phases of a card game room's lifecycle.
 * Transitions: LOBBY → SUBMITTING → JUDGING → ROUND_OVER → SUBMITTING (loop)
 *              ROUND_OVER → GAME_OVER (when winning score reached)
 */
public enum GamePhase {
    LOBBY,
    SUBMITTING,
    JUDGING,
    ROUND_OVER,
    GAME_OVER
}
