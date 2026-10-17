package com.cardgame.service;

import com.cardgame.model.*;
import com.cardgame.service.StatePublisher.RoomSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Core game logic with per-room synchronization.
 * <p>
 * Concurrency model:
 * <ul>
 *   <li>Every method that mutates a GameRoom acquires synchronized(room) first</li>
 *   <li>Snapshot is captured INSIDE the lock</li>
 *   <li>Publishing (network I/O) happens OUTSIDE the lock</li>
 *   <li>Validation is complete before any mutation begins (no partial state changes)</li>
 * </ul>
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final RoomManager roomManager;
    private final DeckService deckService;
    private final GameSessionRegistry sessionRegistry;
    private final StatePublisher statePublisher;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public GameService(RoomManager roomManager, DeckService deckService,
                       GameSessionRegistry sessionRegistry, StatePublisher statePublisher) {
        this.roomManager = roomManager;
        this.deckService = deckService;
        this.sessionRegistry = sessionRegistry;
        this.statePublisher = statePublisher;
    }

    /**
     * Creates a new room and adds the creator as host.
     */
    public void createRoom(String sessionId, String username) {
        if (username == null || username.isBlank()) {
            statePublisher.sendError(sessionId, "Username is required", null);
            return;
        }
        if (sessionRegistry.isRegistered(sessionId)) {
            sessionRegistry.unregister(sessionId);
        }

        GameRoom room;
        try {
            room = roomManager.createRoom();
        } catch (IllegalStateException e) {
            statePublisher.sendError(sessionId, e.getMessage(), null);
            return;
        }

        RoomSnapshot snapshot;
        synchronized (room) {
            String playerId = UUID.randomUUID().toString();
            Player host = new Player(playerId, sessionId, username.trim(), true);
            room.addPlayer(host);
            room.touchActivity();
            sessionRegistry.register(sessionId, room.getRoomId(), playerId);
            snapshot = StatePublisher.captureSnapshot(room, username + " created the room");
        }

        statePublisher.sendRoomCreated(sessionId, snapshot);
        statePublisher.publishRoomUpdate(snapshot);
    }

    /**
     * Adds a player to an existing room.
     */
    public void joinRoom(String sessionId, String roomId, String username) {
        if (username == null || username.isBlank()) {
            statePublisher.sendError(sessionId, "Username is required", null);
            return;
        }
        if (sessionRegistry.isRegistered(sessionId)) {
            sessionRegistry.unregister(sessionId);
        }

        Optional<GameRoom> optRoom = roomManager.getRoom(roomId);
        if (optRoom.isEmpty()) {
            statePublisher.sendError(sessionId, "Room not found: " + roomId, null);
            return;
        }

        GameRoom room = optRoom.get();
        RoomSnapshot snapshot;
        Player targetPlayer = null;

        synchronized (room) {
            Optional<Player> existingPlayer = room.getPlayers().stream()
                    .filter(p -> p.getUsername().equalsIgnoreCase(username.trim()))
                    .findFirst();

            if (existingPlayer.isPresent()) {
                Player player = existingPlayer.get();
                player.setSessionId(sessionId);
                player.setConnected(true);
                sessionRegistry.register(sessionId, room.getRoomId(), player.getId());
                snapshot = StatePublisher.captureSnapshot(room, username.trim() + " reconnected");
                targetPlayer = player;
            } else {
                if (room.getPhase() == GamePhase.GAME_OVER) {
                    statePublisher.sendError(sessionId, "This game has already ended", roomId);
                    return;
                }
                if (room.getPlayers().size() >= GameRoom.getMaxPlayers()) {
                    statePublisher.sendError(sessionId, "Room is full", roomId);
                    return;
                }

                String playerId = UUID.randomUUID().toString();
                Player player = new Player(playerId, sessionId, username.trim(), false);
                if (room.getPhase() != GamePhase.LOBBY) {
                    player.addToHand(room.drawResponses(GameRoom.getHandSize()));
                }
                room.addPlayer(player);
                room.touchActivity();
                sessionRegistry.register(sessionId, room.getRoomId(), playerId);
                snapshot = StatePublisher.captureSnapshot(room, username.trim() + " joined the room");
                targetPlayer = player;
            }
        }

        statePublisher.publishRoomUpdate(snapshot);
        if (targetPlayer != null && !targetPlayer.getHand().isEmpty()) {
            sendHand(sessionId, room.getRoomId(), targetPlayer.getId());
        }
    }

    /**
     * Starts the game. Only the host can start, and only with enough players.
     */
    public void startGame(String sessionId, String roomId) {
        startGame(sessionId, roomId, null, null, null);
    }

    public void startGame(String sessionId, String roomId, String playerId) {
        startGame(sessionId, roomId, playerId, null, null);
    }

    public void startGame(String sessionId, String roomId, String playerId, Integer maxRounds, Integer timerSeconds) {
        var resolved = resolveAndValidate(sessionId, roomId, playerId);
        if (resolved == null) return;

        GameRoom room = resolved.room;
        RoomSnapshot snapshot;

        synchronized (room) {
            Player player = room.findPlayerById(resolved.playerId).orElse(null);
            if (player == null || !player.isHost()) {
                statePublisher.sendError(sessionId, "Only the host can start the game", roomId);
                return;
            }
            if (room.getPhase() != GamePhase.LOBBY) {
                statePublisher.sendError(sessionId, "Game is not in lobby phase", roomId);
                return;
            }
            int connected = room.getConnectedPlayerCount();
            if (connected < GameRoom.getMinPlayers()) {
                statePublisher.sendError(sessionId,
                        "Need at least " + GameRoom.getMinPlayers() + " players (have " + connected + ")",
                        roomId);
                return;
            }

            // Configure rounds and timer
            int rounds = (maxRounds != null && maxRounds > 0) ? maxRounds : 5;
            int timer = (timerSeconds != null && timerSeconds >= 0) ? timerSeconds : 60;
            room.setMaxRounds(rounds);
            room.setSubmissionTimerSeconds(timer);
            room.setCurrentRound(1);

            // Initialize decks and deal hands
            room.initializeDecks(deckService.getPromptDefinitions(), deckService.getResponseDefinitions());
            room.dealHands();
            room.setJudgeIndex(0);

            // Draw first prompt
            PromptCard prompt = room.drawPrompt();
            if (prompt == null) {
                statePublisher.sendError(sessionId, "No prompt cards available", roomId);
                return;
            }
            room.setCurrentPrompt(prompt);
            room.setPhase(GamePhase.SUBMITTING);
            if (timer > 0) {
                room.startSubmissionTimer();
            }
            room.touchActivity();

            snapshot = StatePublisher.captureSnapshot(room, "Game started! Round 1 of " + rounds + ". " + room.getJudge().getUsername() + " is the judge.");
        }

        statePublisher.publishRoomUpdate(snapshot);
        publishAllPlayerHands(room);

        if (room.getSubmissionTimerSeconds() > 0) {
            scheduleSubmissionTimeout(room.getRoomId(), room.getCurrentRound(), room.getSubmissionTimerSeconds());
        }
    }

    /**
     * Submits response cards for the current round.
     * Full validation: ownership, uniqueness, count, phase, eligibility.
     */
    public void submitCards(String sessionId, String roomId, List<String> cardIds) {
        submitCards(sessionId, roomId, null, cardIds);
    }

    public void submitCards(String sessionId, String roomId, String playerId, List<String> cardIds) {
        var resolved = resolveAndValidate(sessionId, roomId, playerId);
        if (resolved == null) return;

        GameRoom room = resolved.room;
        RoomSnapshot snapshot;

        synchronized (room) {
            if (room.getPhase() != GamePhase.SUBMITTING) {
                statePublisher.sendError(sessionId, "Not in submission phase", roomId);
                return;
            }
            Player player = room.findPlayerById(resolved.playerId).orElse(null);
            if (player == null) {
                statePublisher.sendError(sessionId, "Player not found in room", roomId);
                return;
            }
            if (room.isJudge(player.getId())) {
                statePublisher.sendError(sessionId, "The judge cannot submit cards", roomId);
                return;
            }
            if (player.hasSubmitted()) {
                statePublisher.sendError(sessionId, "You have already submitted", roomId);
                return;
            }
            if (cardIds == null || cardIds.isEmpty()) {
                statePublisher.sendError(sessionId, "No cards selected", roomId);
                return;
            }

            // Validate pick count
            int requiredPick = room.getCurrentPrompt().pickCount();
            if (cardIds.size() != requiredPick) {
                statePublisher.sendError(sessionId,
                        "Must submit exactly " + requiredPick + " card(s)", roomId);
                return;
            }

            // Validate unique card IDs
            if (new HashSet<>(cardIds).size() != cardIds.size()) {
                statePublisher.sendError(sessionId, "Duplicate card IDs in submission", roomId);
                return;
            }

            // Validate card ownership
            if (!player.holdsCards(cardIds)) {
                statePublisher.sendError(sessionId, "You don't have all those cards", roomId);
                return;
            }

            // --- All validation passed. Now mutate atomically. ---

            // Collect the actual card objects before removing
            List<ResponseCard> submittedCards = new ArrayList<>();
            for (String cardId : cardIds) {
                player.getHand().stream()
                        .filter(c -> c.id().equals(cardId))
                        .findFirst()
                        .ifPresent(submittedCards::add);
            }

            // Remove from hand
            player.removeFromHand(cardIds);

            // Draw replacements
            List<ResponseCard> replacements = room.drawResponses(cardIds.size());
            player.addToHand(replacements);

            // Record submission
            Submission submission = new Submission(player.getId(), submittedCards);
            room.getRoundSubmissions().add(submission);
            player.setHasSubmitted(true);

            // Check if all eligible players have submitted
            String message = player.getUsername() + " submitted";
            if (room.allEligiblePlayersSubmitted()) {
                // Shuffle submissions so judge can't infer by order
                Collections.shuffle(room.getRoundSubmissions());
                room.setPhase(GamePhase.JUDGING);
                room.clearSubmissionTimer();
                message = "All cards are in! " + room.getJudge().getUsername() + " is judging.";
            }

            room.touchActivity();
            snapshot = StatePublisher.captureSnapshot(room, message);
        }

        statePublisher.publishRoomUpdate(snapshot);
    }

    /**
     * Judge picks the winning submission.
     */
    public void judgeSubmission(String sessionId, String roomId, String winningSubmissionId) {
        judgeSubmission(sessionId, roomId, null, winningSubmissionId);
    }

    public void judgeSubmission(String sessionId, String roomId, String playerId, String winningSubmissionId) {
        var resolved = resolveAndValidate(sessionId, roomId, playerId);
        if (resolved == null) return;

        GameRoom room = resolved.room;
        RoomSnapshot snapshot;

        synchronized (room) {
            if (room.getPhase() != GamePhase.JUDGING) {
                statePublisher.sendError(sessionId, "Not in judging phase", roomId);
                return;
            }
            if (!room.isJudge(resolved.playerId)) {
                statePublisher.sendError(sessionId, "You are not the judge", roomId);
                return;
            }
            if (winningSubmissionId == null || winningSubmissionId.isBlank()) {
                statePublisher.sendError(sessionId, "Must select a submission", roomId);
                return;
            }

            // Find winning submission
            Submission winningSub = room.getRoundSubmissions().stream()
                    .filter(s -> s.getSubmissionId().equals(winningSubmissionId))
                    .findFirst()
                    .orElse(null);
            if (winningSub == null) {
                statePublisher.sendError(sessionId, "Invalid submission ID", roomId);
                return;
            }

            // Award point
            Player winner = room.findPlayerById(winningSub.getPlayerId()).orElse(null);
            if (winner != null) {
                winner.incrementScore();
            }

            room.setWinningSubmissionId(winningSubmissionId);
            room.setWinningPlayerId(winningSub.getPlayerId());

            // Check win condition (either reached winning score or reached max rounds)
            String message;
            boolean isGameOver = (winner != null && winner.getScore() >= room.getWinningScore())
                    || room.isGameComplete();

            if (isGameOver) {
                room.setPhase(GamePhase.GAME_OVER);
                message = winner != null
                        ? winner.getUsername() + " wins the game with " + winner.getScore() + " points!"
                        : "Game over! All rounds completed.";
            } else {
                room.setPhase(GamePhase.ROUND_OVER);
                String winnerName = winner != null ? winner.getUsername() : "Unknown player";
                Player nextJudge = room.getNextJudge();
                String nextJudgeName = nextJudge != null ? nextJudge.getUsername() : "the next player";
                message = winnerName + " won this round! Next judge: " + nextJudgeName;
            }

            room.touchActivity();
            snapshot = StatePublisher.captureSnapshot(room, message);
        }

        statePublisher.publishRoomUpdate(snapshot);

        // If game continues, automatically rotate judge and start next round after 5 seconds!
        // No permission from room owner needed.
        if (snapshot.phase() == GamePhase.ROUND_OVER) {
            scheduleAutoNextRound(room.getRoomId(), snapshot.currentRound(), 5);
        }
    }

    /**
     * Advances to the next round. Can be triggered automatically or manually.
     */
    public void nextRound(String sessionId, String roomId) {
        nextRound(sessionId, roomId, null);
    }

    public void nextRound(String sessionId, String roomId, String playerId) {
        var resolved = resolveAndValidate(sessionId, roomId, playerId);
        if (resolved == null) return;
        autoAdvanceRound(resolved.room.getRoomId(), resolved.room.getCurrentRound());
    }

    private void scheduleAutoNextRound(String roomId, int fromRound, int delaySeconds) {
        scheduler.schedule(() -> {
            try {
                autoAdvanceRound(roomId, fromRound);
            } catch (Exception e) {
                log.error("Error auto-advancing round for room {}", roomId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public void autoAdvanceRound(String roomId, int expectedRound) {
        Optional<GameRoom> optRoom = roomManager.getRoom(roomId);
        if (optRoom.isEmpty()) return;
        GameRoom room = optRoom.get();

        RoomSnapshot snapshot = null;
        synchronized (room) {
            // Only advance if currently in ROUND_OVER and for the expected round
            if (room.getPhase() != GamePhase.ROUND_OVER || room.getCurrentRound() != expectedRound) {
                return;
            }

            // Clear round state
            room.clearRound();
            room.incrementRound();

            if (room.isGameComplete()) {
                room.setPhase(GamePhase.GAME_OVER);
                room.touchActivity();
                snapshot = StatePublisher.captureSnapshot(room, "All rounds completed! Game over.");
            } else {
                // Automatically rotate judge without permission of room owner
                room.rotateJudge();

                // Draw new prompt
                PromptCard prompt = room.drawPrompt();
                if (prompt == null) {
                    room.setPhase(GamePhase.GAME_OVER);
                    room.touchActivity();
                    snapshot = StatePublisher.captureSnapshot(room, "No more prompt cards! Game over.");
                } else {
                    room.setCurrentPrompt(prompt);
                    room.setPhase(GamePhase.SUBMITTING);
                    if (room.getSubmissionTimerSeconds() > 0) {
                        room.startSubmissionTimer();
                    }
                    room.touchActivity();
                    snapshot = StatePublisher.captureSnapshot(room,
                            "Round " + room.getCurrentRound() + " of " + room.getMaxRounds() + "! "
                                    + room.getJudge().getUsername() + " is the judge.");
                }
            }
        }

        if (snapshot != null) {
            statePublisher.publishRoomUpdate(snapshot);
            if (snapshot.phase() == GamePhase.SUBMITTING) {
                publishAllPlayerHands(room);
                if (snapshot.submissionTimerSeconds() > 0) {
                    scheduleSubmissionTimeout(roomId, snapshot.currentRound(), snapshot.submissionTimerSeconds());
                }
            }
        }
    }

    private void scheduleSubmissionTimeout(String roomId, int roundNumber, int delaySeconds) {
        if (delaySeconds <= 0) return;
        scheduler.schedule(() -> {
            try {
                handleSubmissionTimeout(roomId, roundNumber);
            } catch (Exception e) {
                log.error("Error handling submission timeout for room {}", roomId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void handleSubmissionTimeout(String roomId, int expectedRound) {
        Optional<GameRoom> optRoom = roomManager.getRoom(roomId);
        if (optRoom.isEmpty()) return;
        GameRoom room = optRoom.get();

        RoomSnapshot snapshot = null;
        List<Player> autoSubmittedPlayers = new ArrayList<>();

        synchronized (room) {
            if (room.getPhase() != GamePhase.SUBMITTING || room.getCurrentRound() != expectedRound) {
                return;
            }

            int requiredPick = room.getCurrentPrompt() != null ? room.getCurrentPrompt().pickCount() : 1;

            // Auto-submit first eligible cards for any players who haven't submitted
            for (Player player : room.getEligibleSubmitters()) {
                if (!player.hasSubmitted() && !player.getHand().isEmpty()) {
                    List<ResponseCard> hand = player.getHand();
                    int count = Math.min(requiredPick, hand.size());
                    List<ResponseCard> selected = new ArrayList<>(hand.subList(0, count));
                    List<String> cardIds = selected.stream().map(ResponseCard::id).toList();

                    player.removeFromHand(cardIds);
                    List<ResponseCard> replacements = room.drawResponses(cardIds.size());
                    player.addToHand(replacements);

                    Submission submission = new Submission(player.getId(), selected);
                    room.getRoundSubmissions().add(submission);
                    player.setHasSubmitted(true);
                    autoSubmittedPlayers.add(player);
                }
            }

            // Advance to JUDGING phase
            room.clearSubmissionTimer();
            Collections.shuffle(room.getRoundSubmissions());
            room.setPhase(GamePhase.JUDGING);
            room.touchActivity();
            snapshot = StatePublisher.captureSnapshot(room, "Time's up! Cards submitted automatically. " + room.getJudge().getUsername() + " is judging.");
        }

        if (snapshot != null) {
            statePublisher.publishRoomUpdate(snapshot);
            for (Player p : autoSubmittedPlayers) {
                sendHand(p.getSessionId(), roomId, p.getId());
            }
        }
    }

    private void publishAllPlayerHands(GameRoom room) {
        for (Player p : room.getPlayers()) {
            if (p.isConnected()) {
                sendHand(p.getSessionId(), room.getRoomId(), p.getId());
            }
        }
    }

    /**
     * Handles a player disconnecting.
     */
    public void handleDisconnect(String sessionId) {
        var mapping = sessionRegistry.unregister(sessionId);
        if (mapping.isEmpty()) return;

        String roomId = mapping.get().roomId();
        String playerId = mapping.get().playerId();

        Optional<GameRoom> optRoom = roomManager.getRoom(roomId);
        if (optRoom.isEmpty()) return;

        GameRoom room = optRoom.get();
        RoomSnapshot snapshot;

        synchronized (room) {
            Player player = room.findPlayerById(playerId).orElse(null);
            if (player == null) return;

            String username = player.getUsername();
            player.setConnected(false);
            room.touchActivity();
            snapshot = StatePublisher.captureSnapshot(room, username + " disconnected");
        }

        statePublisher.publishRoomUpdate(snapshot);
    }

    /**
     * Resends a player's private hand upon request (e.g. after refresh or connection recovery).
     */
    public void sendHand(String sessionId, String roomId) {
        sendHand(sessionId, roomId, null);
    }

    public void sendHand(String sessionId, String roomId, String playerId) {
        var resolved = resolveAndValidate(sessionId, roomId, playerId);
        if (resolved == null) return;

        GameRoom room = resolved.room;
        StatePublisher.PlayerSnapshot playerSnapshot = null;

        synchronized (room) {
            Player player = room.findPlayerById(resolved.playerId).orElse(null);
            if (player != null) {
                Player judge = room.getJudge();
                boolean isJudge = judge != null && judge.getId().equals(player.getId());
                playerSnapshot = new StatePublisher.PlayerSnapshot(
                        player.getId(),
                        player.getSessionId(),
                        player.getUsername(),
                        player.getScore(),
                        player.isHost(),
                        player.hasSubmitted(),
                        isJudge,
                        player.isConnected(),
                        List.copyOf(player.getHand()),
                        player.getHand().size()
                );
            }
        }

        if (playerSnapshot != null) {
            statePublisher.publishSinglePlayerHand(room.getRoomId(), playerSnapshot);
        }
    }

    // --- Internal helpers ---

    private record ResolvedSession(GameRoom room, String playerId) {}

    /**
     * Resolves session → (room, player) and validates membership.
     * Supports dynamic re-binding when playerId is passed by client.
     */
    private ResolvedSession resolveAndValidate(String sessionId, String roomId) {
        return resolveAndValidate(sessionId, roomId, null);
    }

    private ResolvedSession resolveAndValidate(String sessionId, String roomId, String playerId) {
        if (roomId == null) {
            statePublisher.sendError(sessionId, "Room ID is required", null);
            return null;
        }

        Optional<GameRoom> optRoom = roomManager.getRoom(roomId.trim().toUpperCase());
        if (optRoom.isEmpty()) {
            statePublisher.sendError(sessionId, "Room no longer exists: " + roomId, roomId);
            return null;
        }

        GameRoom room = optRoom.get();

        // 1. If explicit playerId is provided, verify membership and dynamically re-bind session
        if (playerId != null && !playerId.isBlank()) {
            Optional<Player> optPlayer = room.findPlayerById(playerId);
            if (optPlayer.isPresent()) {
                Player player = optPlayer.get();
                player.setConnected(true);
                player.setSessionId(sessionId);
                sessionRegistry.register(sessionId, room.getRoomId(), player.getId());
                return new ResolvedSession(room, player.getId());
            }
        }

        // 2. Fallback to existing session mapping
        var mapping = sessionRegistry.resolve(sessionId);
        if (mapping.isPresent() && mapping.get().roomId().equalsIgnoreCase(roomId)) {
            return new ResolvedSession(room, mapping.get().playerId());
        }

        statePublisher.sendError(sessionId, "Not registered to any room", roomId);
        return null;
    }
}
