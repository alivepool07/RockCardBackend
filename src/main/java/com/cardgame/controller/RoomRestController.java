package com.cardgame.controller;

import com.cardgame.dto.GameMessages.CreateRoomRequest;
import com.cardgame.dto.GameMessages.JoinRoomRequest;
import com.cardgame.dto.RoomStateDTO;
import com.cardgame.model.GameRoom;
import com.cardgame.model.Player;
import com.cardgame.service.DeckService;
import com.cardgame.service.GameSessionRegistry;
import com.cardgame.service.RoomManager;
import com.cardgame.service.StatePublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for instant, deterministic Room Creation and Joining.
 * Ensures the creator/joiner receives their room code and enters the lobby
 * immediately without relying on async WebSocket handshake timing.
 */
@RestController
@RequestMapping("/api/room")
@CrossOrigin(origins = "*")
public class RoomRestController {

    private final RoomManager roomManager;
    private final GameSessionRegistry sessionRegistry;
    private final StatePublisher statePublisher;
    private final com.cardgame.service.GameService gameService;

    public record RoomActionResponse(
            String roomId,
            String playerId,
            RoomStateDTO roomState
    ) {}

    public record StartGameRequest(String roomId, String playerId, Integer maxRounds, Integer timerSeconds) {}
    public record SubmitCardsRequest(String roomId, String playerId, List<String> cardIds) {}
    public record JudgeRequest(String roomId, String playerId, String judgeId, String winningSubmissionId) {}
    public record NextRoundRequest(String roomId, String playerId) {}

    public RoomRestController(RoomManager roomManager,
                              GameSessionRegistry sessionRegistry,
                              StatePublisher statePublisher,
                              com.cardgame.service.GameService gameService) {
        this.roomManager = roomManager;
        this.sessionRegistry = sessionRegistry;
        this.statePublisher = statePublisher;
        this.gameService = gameService;
    }

    @PostMapping("/create")
    public ResponseEntity<?> createRoom(@RequestBody CreateRoomRequest request) {
        String username = request.username();
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }

        GameRoom room;
        try {
            room = roomManager.createRoom();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        String playerId = UUID.randomUUID().toString();
        String sessionKey = "user-" + UUID.randomUUID().toString().substring(0, 8);
        StatePublisher.RoomSnapshot snapshot;

        synchronized (room) {
            Player host = new Player(playerId, sessionKey, username.trim(), true);
            room.addPlayer(host);
            room.touchActivity();
            sessionRegistry.register(sessionKey, room.getRoomId(), playerId);
            snapshot = StatePublisher.captureSnapshot(room, username.trim() + " created the room");
        }

        // Broadcast to WebSocket topic as well
        statePublisher.publishRoomUpdate(snapshot);

        // Immediate HTTP response with room code and player ID
        return ResponseEntity.ok(new RoomActionResponse(
                room.getRoomId(),
                playerId,
                statePublisher.buildPublicState(snapshot)
        ));
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(@RequestBody JoinRoomRequest request) {
        String username = request.username();
        String roomId = request.roomId();

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room code is required"));
        }

        Optional<GameRoom> optRoom = roomManager.getRoom(roomId.trim().toUpperCase());
        if (optRoom.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room not found: " + roomId));
        }

        GameRoom room = optRoom.get();
        String playerId;
        String sessionKey = "user-" + UUID.randomUUID().toString().substring(0, 8);
        StatePublisher.RoomSnapshot snapshot;

        synchronized (room) {
            // Check if this username belongs to a disconnected player (rejoin)
            Optional<Player> existingPlayer = room.getPlayers().stream()
                    .filter(p -> p.getUsername().equalsIgnoreCase(username.trim()))
                    .findFirst();

            if (existingPlayer.isPresent()) {
                Player player = existingPlayer.get();
                // Allow rejoin: rebind session and mark connected
                player.setSessionId(sessionKey);
                player.setConnected(true);
                playerId = player.getId();
                sessionRegistry.register(sessionKey, room.getRoomId(), playerId);
                snapshot = StatePublisher.captureSnapshot(room, username.trim() + " reconnected");
            } else {
                // New player join
                if (room.getPhase() == com.cardgame.model.GamePhase.GAME_OVER) {
                    return ResponseEntity.badRequest().body(Map.of("error", "This game has already ended."));
                }
                if (room.getPlayers().size() >= GameRoom.getMaxPlayers()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Room is full"));
                }

                playerId = UUID.randomUUID().toString();
                Player player = new Player(playerId, sessionKey, username.trim(), false);
                if (room.getPhase() != com.cardgame.model.GamePhase.LOBBY) {
                    player.addToHand(room.drawResponses(GameRoom.getHandSize()));
                }
                room.addPlayer(player);
                room.touchActivity();
                sessionRegistry.register(sessionKey, room.getRoomId(), playerId);
                snapshot = StatePublisher.captureSnapshot(room, username.trim() + " joined the room");
            }
        }

        // Broadcast update to all room members
        statePublisher.publishRoomUpdate(snapshot);

        // If player has cards, publish their hand immediately
        Player joinedPlayer = room.findPlayerById(playerId).orElse(null);
        if (joinedPlayer != null && !joinedPlayer.getHand().isEmpty()) {
            StatePublisher.PlayerSnapshot ps = new StatePublisher.PlayerSnapshot(
                    joinedPlayer.getId(),
                    joinedPlayer.getSessionId(),
                    joinedPlayer.getUsername(),
                    joinedPlayer.getScore(),
                    joinedPlayer.isHost(),
                    joinedPlayer.hasSubmitted(),
                    room.isJudge(joinedPlayer.getId()),
                    joinedPlayer.isConnected(),
                    List.copyOf(joinedPlayer.getHand()),
                    joinedPlayer.getHand().size()
            );
            statePublisher.publishSinglePlayerHand(room.getRoomId(), ps);
        }

        return ResponseEntity.ok(new RoomActionResponse(
                room.getRoomId(),
                playerId,
                statePublisher.buildPublicState(snapshot)
        ));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<?> getRoomState(@PathVariable String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room ID is required"));
        }
        Optional<GameRoom> optRoom = roomManager.getRoom(roomId.trim().toUpperCase());
        if (optRoom.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Room not found: " + roomId));
        }

        GameRoom room = optRoom.get();
        StatePublisher.RoomSnapshot snapshot;
        synchronized (room) {
            snapshot = StatePublisher.captureSnapshot(room, null);
        }

        return ResponseEntity.ok(statePublisher.buildPublicState(snapshot));
    }

    @PostMapping("/leave")
    public ResponseEntity<?> leaveRoom(@RequestBody Map<String, String> body) {
        String roomId = body.get("roomId");
        String playerId = body.get("playerId");

        if (roomId != null && playerId != null) {
            roomManager.getRoom(roomId.trim().toUpperCase()).ifPresent(room -> {
                StatePublisher.RoomSnapshot snapshot = null;
                synchronized (room) {
                    Player p = room.findPlayerById(playerId).orElse(null);
                    if (p != null) {
                        room.removePlayer(p);
                        if (p.isHost()) {
                            p.setHost(false);
                            room.getPlayers().stream().findFirst().ifPresent(next -> next.setHost(true));
                        }
                        if (room.getPlayers().isEmpty()) {
                            roomManager.removeRoom(room.getRoomId());
                            return;
                        }
                        snapshot = StatePublisher.captureSnapshot(room, p.getUsername() + " left the room");
                    }
                }
                if (snapshot != null) {
                    statePublisher.publishRoomUpdate(snapshot);
                }
            });
        }
        return ResponseEntity.ok(Map.of("status", "left"));
    }

    @PostMapping("/start")
    public ResponseEntity<?> startGame(@RequestBody StartGameRequest req) {
        if (req.roomId() == null || req.roomId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room ID is required"));
        }
        Optional<GameRoom> optRoom = roomManager.getRoom(req.roomId().trim().toUpperCase());
        if (optRoom.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room not found: " + req.roomId()));
        }
        GameRoom room = optRoom.get();
        String sessionKey = "rest-" + UUID.randomUUID().toString().substring(0, 8);
        gameService.startGame(sessionKey, room.getRoomId(), req.playerId(), req.maxRounds(), req.timerSeconds());

        StatePublisher.RoomSnapshot snapshot;
        synchronized (room) {
            snapshot = StatePublisher.captureSnapshot(room, null);
        }
        return ResponseEntity.ok(statePublisher.buildPublicState(snapshot));
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submitCards(@RequestBody SubmitCardsRequest req) {
        if (req.roomId() == null || req.cardIds() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room ID and card IDs required"));
        }
        String sessionKey = "rest-" + UUID.randomUUID().toString().substring(0, 8);
        gameService.submitCards(sessionKey, req.roomId(), req.playerId(), req.cardIds());
        return ResponseEntity.ok(Map.of("status", "submitted"));
    }

    @PostMapping("/judge")
    public ResponseEntity<?> judge(@RequestBody JudgeRequest req) {
        if (req.roomId() == null || req.winningSubmissionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room ID and winning submission ID required"));
        }
        String sessionKey = "rest-" + UUID.randomUUID().toString().substring(0, 8);
        String judgePlayerId = (req.playerId() != null && !req.playerId().isBlank()) ? req.playerId() : req.judgeId();
        gameService.judgeSubmission(sessionKey, req.roomId(), judgePlayerId, req.winningSubmissionId());
        return ResponseEntity.ok(Map.of("status", "judged"));
    }

    @PostMapping("/next-round")
    public ResponseEntity<?> nextRound(@RequestBody NextRoundRequest req) {
        if (req.roomId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room ID is required"));
        }
        String sessionKey = "rest-" + UUID.randomUUID().toString().substring(0, 8);
        gameService.nextRound(sessionKey, req.roomId(), req.playerId());
        return ResponseEntity.ok(Map.of("status", "next-round-started"));
    }

    @GetMapping("/{roomId}/hand/{playerId}")
    public ResponseEntity<?> getPlayerHand(@PathVariable String roomId, @PathVariable String playerId) {
        Optional<GameRoom> optRoom = roomManager.getRoom(roomId.trim().toUpperCase());
        if (optRoom.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Room not found"));
        }
        GameRoom room = optRoom.get();
        synchronized (room) {
            Player player = room.findPlayerById(playerId).orElse(null);
            if (player == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Player not found"));
            }
            return ResponseEntity.ok(Map.of(
                    "playerId", player.getId(),
                    "hand", player.getHand(),
                    "hasSubmitted", player.hasSubmitted(),
                    "isJudge", room.isJudge(player.getId())
            ));
        }
    }
}
