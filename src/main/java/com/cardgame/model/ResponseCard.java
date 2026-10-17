package com.cardgame.model;

/**
 * An immutable response card definition (the "white card").
 * Loaded once from resources and shared across all rooms.
 */
public record ResponseCard(String id, String text) {
}
