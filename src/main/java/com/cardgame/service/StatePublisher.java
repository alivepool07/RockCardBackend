package com.cardgame.service;

import com.cardgame.dto.*;
import com.cardgame.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Responsible for building and publishing game state updates.
 * <p>
 * Key design decisions:
 * <ul>
 *   <li>Public state (room topic): ONE broadcast per room update, reused for all players</li>
 *   <li>Private state (user queue): Individual messages per player with their hand</li>
 *   <li>All state is built from a snapshot taken INSIDE the room lock,
 *       but actual network publishing happens OUTSIDE the lock</li>
 * </ul>
 */
@Service
public class StatePublisher {

    private static final Logger log = LoggerFactory.getLogger(StatePublisher.class);
    private final SimpMessagingTemplate messagingTemplate;

    public StatePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Builds and publishes both public room state and private player states.
     * This method must be called OUTSIDE any synchronized block on the room.
     *
     * @param snapshot an immutable snapshot of room state captured while holding the lock
     */
    public void publishRoomUpdate(RoomSnapshot snapshot) {
        // 1. Build the single public DTO (reused for all players)
        RoomStateDTO publicState = buildPublicState(snapshot);

        // 2. Broadcast public state to room topic
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend(
                    "/topic/room/" + snapshot.roomId(), publicState);
        }

        // 3. Send private hand to each connected player via user queue AND player topic
        for (PlayerSnapshot player : snapshot.players()) {
            if (player.connected()) {
                publishSinglePlayerHand(snapshot.roomId(), player);
            }
        }
    }

    /**
     * Publishes a player's private hand directly to their user queue and player topic.
     */
    public void publishSinglePlayerHand(String roomId, PlayerSnapshot player) {
        if (messagingTemplate == null) return;
        PrivatePlayerStateDTO privateState = new PrivatePlayerStateDTO(
                player.playerId(),
                player.hand(),
                player.hasSubmitted(),
                player.isJudge()
        );
        // User queue
        messagingTemplate.convertAndSendToUser(
                player.sessionId(),
                "/queue/hand",
                privateState
        );
        // Player-specific topic for bulletproof fallback
        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/player/" + player.playerId() + "/hand",
                privateState
        );
    }

    /**
     * Sends an error message to a specific user's session.
     */
    public void sendError(String sessionId, String error, String roomId) {
        if (messagingTemplate == null) return;
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/errors",
                new GameMessages.ErrorResponse(error, roomId)
        );
    }

    /**
     * Sends a created room response directly to the creating user.
     */
    public void sendRoomCreated(String sessionId, RoomSnapshot snapshot) {
        if (messagingTemplate == null) return;
        RoomStateDTO publicState = buildPublicState(snapshot);
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/room-created",
                publicState
        );

        // Also send initial private state
        for (PlayerSnapshot player : snapshot.players()) {
            if (player.sessionId().equals(sessionId)) {
                PrivatePlayerStateDTO privateState = new PrivatePlayerStateDTO(
                        player.playerId(),
                        player.hand(),
                        player.hasSubmitted(),
                        player.isJudge()
                );
                messagingTemplate.convertAndSendToUser(
                        sessionId,
                        "/queue/hand",
                        privateState
                );
            }
        }
    }

    // --- State building helpers ---

    public RoomStateDTO buildPublicState(RoomSnapshot snapshot) {
        List<PlayerDTO> playerDTOs = snapshot.players().stream()
                .map(p -> new PlayerDTO(
                        p.playerId(),
                        p.username(),
                        p.score(),
                        p.isHost(),
                        p.hasSubmitted(),
                        p.isJudge(),
                        p.connected(),
                        p.handSize()
                ))
                .toList();

        List<SubmissionDTO> submissions = null;
        List<RevealedSubmissionDTO> revealedSubmissions = null;
        String winningPlayerName = null;

        if (snapshot.phase() == GamePhase.JUDGING) {
            // Anonymous submissions — no player IDs
            submissions = snapshot.submissions().stream()
                    .map(s -> new SubmissionDTO(s.getSubmissionId(), s.getCards()))
                    .toList();
        } else if (snapshot.phase() == GamePhase.ROUND_OVER || snapshot.phase() == GamePhase.GAME_OVER) {
            // Revealed submissions with player names
            revealedSubmissions = snapshot.submissions().stream()
                    .map(s -> {
                        String playerName = snapshot.players().stream()
                                .filter(p -> p.playerId().equals(s.getPlayerId()))
                                .map(PlayerSnapshot::username)
                                .findFirst()
                                .orElse("Unknown");
                        boolean isWinner = s.getSubmissionId().equals(snapshot.winningSubmissionId());
                        return new RevealedSubmissionDTO(
                                s.getSubmissionId(), s.getPlayerId(), playerName,
                                s.getCards(), isWinner);
                    })
                    .toList();
            // Find winning player name
            if (snapshot.winningPlayerId() != null) {
                winningPlayerName = snapshot.players().stream()
                        .filter(p -> p.playerId().equals(snapshot.winningPlayerId()))
                        .map(PlayerSnapshot::username)
                        .findFirst()
                        .orElse("Unknown");
            }
        }

        return new RoomStateDTO(
                snapshot.roomId(),
                snapshot.phase(),
                playerDTOs,
                snapshot.currentPrompt(),
                snapshot.judgeName(),
                snapshot.judgeId(),
                snapshot.winningScore(),
                snapshot.maxRounds(),
                snapshot.currentRound(),
                snapshot.submissionTimerSeconds(),
                snapshot.submissionDeadlineEpochMs(),
                submissions,
                revealedSubmissions,
                snapshot.winningSubmissionId(),
                snapshot.winningPlayerId(),
                winningPlayerName,
                snapshot.message()
        );
    }

    // --- Snapshot records for thread-safe state capture ---

    /**
     * Immutable snapshot of room state, captured inside a synchronized block.
     */
    public record RoomSnapshot(
            String roomId,
            GamePhase phase,
            List<PlayerSnapshot> players,
            PromptCard currentPrompt,
            String judgeName,
            String judgeId,
            int winningScore,
            int maxRounds,
            int currentRound,
            int submissionTimerSeconds,
            Long submissionDeadlineEpochMs,
            List<Submission> submissions,
            String winningSubmissionId,
            String winningPlayerId,
            String message
    ) {}

    /**
     * Immutable snapshot of a player's state, including their hand for private delivery.
     */
    public record PlayerSnapshot(
            String playerId,
            String sessionId,
            String username,
            int score,
            boolean isHost,
            boolean hasSubmitted,
            boolean isJudge,
            boolean connected,
            List<ResponseCard> hand,
            int handSize
    ) {}

    /**
     * Captures an immutable snapshot of the room state.
     * MUST be called while holding synchronized(room).
     */
    public static RoomSnapshot captureSnapshot(GameRoom room, String message) {
        Player judge = room.getJudge();
        String judgeName = judge != null ? judge.getUsername() : null;
        String judgeId = judge != null ? judge.getId() : null;

        List<PlayerSnapshot> playerSnapshots = room.getPlayers().stream()
                .map(p -> new PlayerSnapshot(
                        p.getId(),
                        p.getSessionId(),
                        p.getUsername(),
                        p.getScore(),
                        p.isHost(),
                        p.hasSubmitted(),
                        judge != null && p.getId().equals(judge.getId()),
                        p.isConnected(),
                        List.copyOf(p.getHand()),  // Defensive copy of hand
                        p.getHand().size()
                ))
                .toList();

        // Defensive copy of submissions
        List<Submission> submissionsCopy = List.copyOf(room.getRoundSubmissions());

        Long deadlineMs = room.getSubmissionDeadline() != null
                ? room.getSubmissionDeadline().toEpochMilli()
                : null;

        return new RoomSnapshot(
                room.getRoomId(),
                room.getPhase(),
                playerSnapshots,
                room.getCurrentPrompt(),
                judgeName,
                judgeId,
                room.getWinningScore(),
                room.getMaxRounds(),
                room.getCurrentRound(),
                room.getSubmissionTimerSeconds(),
                deadlineMs,
                submissionsCopy,
                room.getWinningSubmissionId(),
                room.getWinningPlayerId(),
                message
        );
    }
}
