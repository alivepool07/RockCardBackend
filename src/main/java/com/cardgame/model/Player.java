package com.cardgame.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a player in a game room.
 * Mutable fields are only modified under the owning GameRoom's lock.
 */
public class Player {

    private final String id;          // Server-assigned, unguessable UUID
    private String sessionId;         // WebSocket session ID for disconnect tracking
    private String username;
    private int score;
    private boolean host;
    private final List<ResponseCard> hand;
    private boolean hasSubmitted;
    private boolean connected;

    public Player(String id, String sessionId, String username, boolean host) {
        this.id = id;
        this.sessionId = sessionId;
        this.username = username;
        this.host = host;
        this.score = 0;
        this.hand = new ArrayList<>();
        this.hasSubmitted = false;
        this.connected = true;
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUsername() {
        return username;
    }

    public int getScore() {
        return score;
    }

    public boolean isHost() {
        return host;
    }

    public List<ResponseCard> getHand() {
        return hand;
    }

    /**
     * Returns an unmodifiable view of the hand for safe external use.
     */
    public List<ResponseCard> getHandView() {
        return Collections.unmodifiableList(hand);
    }

    public boolean hasSubmitted() {
        return hasSubmitted;
    }

    public boolean isConnected() {
        return connected;
    }

    // --- Mutators (only called under GameRoom lock) ---

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void incrementScore() {
        this.score++;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public void setHasSubmitted(boolean hasSubmitted) {
        this.hasSubmitted = hasSubmitted;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * Adds cards to the player's hand.
     */
    public void addToHand(List<ResponseCard> cards) {
        hand.addAll(cards);
    }

    /**
     * Removes specific cards from the player's hand.
     * @return true if all cards were found and removed
     */
    public boolean removeFromHand(List<String> cardIds) {
        for (String cardId : cardIds) {
            boolean removed = hand.removeIf(c -> c.id().equals(cardId));
            if (!removed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the player currently holds all specified cards.
     */
    public boolean holdsCards(List<String> cardIds) {
        for (String cardId : cardIds) {
            if (hand.stream().noneMatch(c -> c.id().equals(cardId))) {
                return false;
            }
        }
        return true;
    }
}
