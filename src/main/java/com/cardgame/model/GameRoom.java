package com.cardgame.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The core mutable game state for a single room.
 * <p>
 * ALL mutations to this object must be performed under its intrinsic lock
 * (synchronized on this instance). Callers must:
 * <ol>
 *   <li>Acquire the lock</li>
 *   <li>Validate + mutate</li>
 *   <li>Capture a snapshot of the state to broadcast</li>
 *   <li>Release the lock</li>
 *   <li>Broadcast OUTSIDE the lock</li>
 * </ol>
 */
public class GameRoom {

    private final String roomId;
    private final List<Player> players = new ArrayList<>();
    private int judgeIndex = 0;
    private GamePhase phase = GamePhase.LOBBY;

    // Decks — per-room mutable stacks
    private final Deque<PromptCard> promptDeck = new ArrayDeque<>();
    private final Deque<ResponseCard> responseDeck = new ArrayDeque<>();
    private final List<PromptCard> promptDiscard = new ArrayList<>();
    private final List<ResponseCard> responseDiscard = new ArrayList<>();

    private PromptCard currentPrompt;
    private final List<Submission> roundSubmissions = new ArrayList<>();

    // Settings
    private int winningScore = 7;
    private int maxRounds = 7;
    private int currentRound = 0;
    private int submissionTimerSeconds = 60;
    private static final int HAND_SIZE = 7;
    private static final int MIN_PLAYERS = 3;
    private static final int MAX_PLAYERS = 20;

    // Lifecycle
    private volatile Instant lastActivity;
    private String winningSubmissionId;
    private String winningPlayerId;
    private Instant submissionDeadline;

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.lastActivity = Instant.now();
    }

    // --- Getters ---

    public String getRoomId() {
        return roomId;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public int getJudgeIndex() {
        return judgeIndex;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public PromptCard getCurrentPrompt() {
        return currentPrompt;
    }

    public List<Submission> getRoundSubmissions() {
        return roundSubmissions;
    }

    public int getWinningScore() {
        return winningScore;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getSubmissionTimerSeconds() {
        return submissionTimerSeconds;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public String getWinningSubmissionId() {
        return winningSubmissionId;
    }

    public String getWinningPlayerId() {
        return winningPlayerId;
    }

    public Instant getSubmissionDeadline() {
        return submissionDeadline;
    }

    // --- Player lookups ---

    public Optional<Player> findPlayerById(String playerId) {
        return players.stream().filter(p -> p.getId().equals(playerId)).findFirst();
    }

    public Optional<Player> findPlayerBySessionId(String sessionId) {
        return players.stream().filter(p -> p.getSessionId().equals(sessionId)).findFirst();
    }

    public Player getJudge() {
        if (players.isEmpty()) return null;
        return players.get(judgeIndex % players.size());
    }

    public boolean isJudge(String playerId) {
        Player judge = getJudge();
        return judge != null && judge.getId().equals(playerId);
    }

    public Player getNextJudge() {
        if (players.isEmpty()) return null;
        int nextIdx = judgeIndex;
        int attempts = 0;
        do {
            nextIdx = (nextIdx + 1) % players.size();
            attempts++;
        } while (!players.get(nextIdx).isConnected() && attempts < players.size());
        return players.get(nextIdx);
    }

    public int getConnectedPlayerCount() {
        return (int) players.stream().filter(Player::isConnected).count();
    }

    public List<Player> getEligibleSubmitters() {
        Player judge = getJudge();
        return players.stream()
                .filter(Player::isConnected)
                .filter(p -> judge == null || !p.getId().equals(judge.getId()))
                .toList();
    }

    public boolean allEligiblePlayersSubmitted() {
        return getEligibleSubmitters().stream().allMatch(Player::hasSubmitted);
    }

    // --- Mutators (MUST be called under synchronized(this)) ---

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public void setJudgeIndex(int judgeIndex) {
        this.judgeIndex = judgeIndex;
    }

    public void setCurrentPrompt(PromptCard currentPrompt) {
        this.currentPrompt = currentPrompt;
    }

    public void setWinningScore(int winningScore) {
        this.winningScore = winningScore;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public void incrementRound() {
        this.currentRound++;
    }

    public void setSubmissionTimerSeconds(int seconds) {
        this.submissionTimerSeconds = seconds;
    }

    public void setWinningSubmissionId(String id) {
        this.winningSubmissionId = id;
    }

    public void setWinningPlayerId(String id) {
        this.winningPlayerId = id;
    }

    public void startSubmissionTimer() {
        this.submissionDeadline = Instant.now().plusSeconds(submissionTimerSeconds);
    }

    public void clearSubmissionTimer() {
        this.submissionDeadline = null;
    }

    public boolean isSubmissionTimerExpired() {
        return submissionDeadline != null && Instant.now().isAfter(submissionDeadline);
    }

    public boolean isGameComplete() {
        return currentRound >= maxRounds;
    }

    public void touchActivity() {
        this.lastActivity = Instant.now();
    }

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }

    // --- Deck operations ---

    public void initializeDecks(List<PromptCard> prompts, List<ResponseCard> responses) {
        promptDeck.clear();
        responseDeck.clear();
        promptDiscard.clear();
        responseDiscard.clear();
        List<PromptCard> shuffledPrompts = new ArrayList<>(prompts);
        List<ResponseCard> shuffledResponses = new ArrayList<>(responses);
        Collections.shuffle(shuffledPrompts);
        Collections.shuffle(shuffledResponses);
        promptDeck.addAll(shuffledPrompts);
        responseDeck.addAll(shuffledResponses);
    }

    /**
     * Draws a prompt card, recycling the discard pile if needed.
     */
    public PromptCard drawPrompt() {
        if (promptDeck.isEmpty()) {
            if (promptDiscard.isEmpty()) {
                return null; // No cards available
            }
            Collections.shuffle(promptDiscard);
            promptDeck.addAll(promptDiscard);
            promptDiscard.clear();
        }
        return promptDeck.poll();
    }

    /**
     * Draws response cards, recycling the discard pile if needed.
     */
    public List<ResponseCard> drawResponses(int count) {
        List<ResponseCard> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (responseDeck.isEmpty()) {
                if (responseDiscard.isEmpty()) {
                    break; // No more cards
                }
                Collections.shuffle(responseDiscard);
                responseDeck.addAll(responseDiscard);
                responseDiscard.clear();
            }
            ResponseCard card = responseDeck.poll();
            if (card != null) {
                drawn.add(card);
            }
        }
        return drawn;
    }

    /**
     * Returns used response cards to the discard pile.
     */
    public void discardResponses(List<ResponseCard> cards) {
        responseDiscard.addAll(cards);
    }

    /**
     * Returns a used prompt card to the discard pile.
     */
    public void discardPrompt(PromptCard card) {
        if (card != null) {
            promptDiscard.add(card);
        }
    }

    /**
     * Deals initial hands to all players.
     */
    public void dealHands() {
        for (Player player : players) {
            player.getHand().clear();
            List<ResponseCard> cards = drawResponses(HAND_SIZE);
            player.addToHand(cards);
        }
    }

    // --- Round management ---

    public void clearRound() {
        // Discard submitted cards
        for (Submission sub : roundSubmissions) {
            discardResponses(sub.getCards());
        }
        roundSubmissions.clear();
        for (Player player : players) {
            player.setHasSubmitted(false);
        }
        winningSubmissionId = null;
        winningPlayerId = null;
        // Discard current prompt
        discardPrompt(currentPrompt);
        currentPrompt = null;
    }

    public void rotateJudge() {
        if (players.isEmpty()) return;
        int attempts = 0;
        do {
            judgeIndex = (judgeIndex + 1) % players.size();
            attempts++;
        } while (!players.get(judgeIndex).isConnected() && attempts < players.size());
    }

    // --- Constants ---

    public static int getHandSize() {
        return HAND_SIZE;
    }

    public static int getMinPlayers() {
        return MIN_PLAYERS;
    }

    public static int getMaxPlayers() {
        return MAX_PLAYERS;
    }
}
