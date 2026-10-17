package com.cardgame.model;

/**
 * An immutable prompt card definition (the "black card").
 * Loaded once from resources and shared across all rooms.
 */
public record PromptCard(String id, String text, int pickCount) {

    public PromptCard {
        if (pickCount < 1) {
            throw new IllegalArgumentException("pickCount must be at least 1");
        }
    }
}
