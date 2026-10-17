package com.cardgame.model;

import java.util.List;
import java.util.UUID;

/**
 * A player's submission for a round. The submissionId is server-generated.
 * playerId is kept hidden from the judge until the round ends.
 */
public class Submission {

    private final String submissionId;
    private final String playerId;
    private final List<ResponseCard> cards;

    public Submission(String playerId, List<ResponseCard> cards) {
        this.submissionId = UUID.randomUUID().toString().substring(0, 8);
        this.playerId = playerId;
        this.cards = List.copyOf(cards); // Immutable copy
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public List<ResponseCard> getCards() {
        return cards;
    }
}
