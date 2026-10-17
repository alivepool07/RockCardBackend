package com.cardgame;

import com.cardgame.dto.GameMessages;
import com.cardgame.model.GamePhase;
import com.cardgame.model.GameRoom;
import com.cardgame.model.Player;
import com.cardgame.model.PromptCard;
import com.cardgame.model.ResponseCard;
import com.cardgame.service.DeckService;
import com.cardgame.service.GameService;
import com.cardgame.service.GameSessionRegistry;
import com.cardgame.service.RoomManager;
import com.cardgame.service.StatePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    private RoomManager roomManager;
    private DeckService deckService;
    private GameSessionRegistry sessionRegistry;
    private StatePublisher statePublisher;
    private GameService gameService;
    private List<StatePublisher.RoomSnapshot> publishedSnapshots = new ArrayList<>();
    private List<String> publishedErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
        ObjectMapper objectMapper = new ObjectMapper();
        deckService = new DeckService(objectMapper);
        try {
            var method = DeckService.class.getDeclaredMethod("loadCards");
            method.setAccessible(true);
            method.invoke(deckService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publishedSnapshots.clear();
        publishedErrors.clear();

        statePublisher = new StatePublisher(null) {
            @Override
            public void publishRoomUpdate(RoomSnapshot snapshot) {
                publishedSnapshots.add(snapshot);
            }

            @Override
            public void sendError(String sessionId, String error, String roomId) {
                publishedErrors.add(error);
            }

            @Override
            public void sendRoomCreated(String sessionId, RoomSnapshot snapshot) {
                publishedSnapshots.add(snapshot);
            }
        };

        sessionRegistry = new GameSessionRegistry();
        gameService = new GameService(roomManager, deckService, sessionRegistry, statePublisher);
    }

    @Test
    @DisplayName("Create room assigns host and registers session")
    void testCreateRoom() {
        gameService.createRoom("session-1", "Alice");

        assertEquals(1, roomManager.getRoomCount());
        assertTrue(sessionRegistry.isRegistered("session-1"));

        var mapping = sessionRegistry.resolve("session-1").orElseThrow();
        GameRoom room = roomManager.getRoom(mapping.roomId()).orElseThrow();

        assertEquals(1, room.getPlayers().size());
        Player host = room.getPlayers().get(0);
        assertEquals("Alice", host.getUsername());
        assertTrue(host.isHost());
        assertEquals(GamePhase.LOBBY, room.getPhase());
    }

    @Test
    @DisplayName("Join room adds player, enforces unique names and lobby phase")
    void testJoinRoom() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();

        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        assertEquals(3, room.getPlayers().size());

        // Attempt duplicate username
        gameService.joinRoom("session-4", roomId, "Alice");
        assertEquals(3, room.getPlayers().size()); // Rejected
    }

    @Test
    @DisplayName("Start game requires host and at least 3 players")
    void testStartGame() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();

        // Try start with 1 player
        gameService.startGame("session-1", roomId);
        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        assertEquals(GamePhase.LOBBY, room.getPhase());

        // Non-host trying to start
        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");
        gameService.startGame("session-2", roomId);
        assertEquals(GamePhase.LOBBY, room.getPhase());

        // Host starts with 3 players
        gameService.startGame("session-1", roomId);
        assertEquals(GamePhase.SUBMITTING, room.getPhase());
        assertNotNull(room.getCurrentPrompt());
        assertEquals(7, room.getPlayers().get(0).getHand().size());
        assertEquals(7, room.getPlayers().get(1).getHand().size());
        assertEquals(7, room.getPlayers().get(2).getHand().size());
    }

    @Test
    @DisplayName("Submissions validate card ownership, pickCount, judge prohibition and advance to JUDGING")
    void testSubmissionsAndJudgingAdvance() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");
        gameService.startGame("session-1", roomId);

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        Player judge = room.getJudge();
        String judgeSessionId = judge.getSessionId();

        // Judge cannot submit
        gameService.submitCards(judgeSessionId, roomId, List.of("dummy-id"));
        assertEquals(0, room.getRoundSubmissions().size());

        // Find submitter players
        Player p2 = room.getPlayers().stream().filter(p -> !p.getId().equals(judge.getId())).findFirst().get();
        Player p3 = room.getPlayers().stream().filter(p -> !p.getId().equals(judge.getId()) && !p.getId().equals(p2.getId())).findFirst().get();

        int pick = room.getCurrentPrompt().pickCount();
        List<String> p2Cards = p2.getHand().subList(0, pick).stream().map(ResponseCard::id).toList();
        List<String> p3Cards = p3.getHand().subList(0, pick).stream().map(ResponseCard::id).toList();

        // p2 submits wrong card count
        gameService.submitCards(p2.getSessionId(), roomId, List.of());
        assertEquals(0, room.getRoundSubmissions().size());

        // p2 submits unowned card
        gameService.submitCards(p2.getSessionId(), roomId, List.of("fake-card-id-xyz"));
        assertEquals(0, room.getRoundSubmissions().size());

        // p2 submits valid cards
        gameService.submitCards(p2.getSessionId(), roomId, p2Cards);
        assertEquals(1, room.getRoundSubmissions().size());
        assertTrue(p2.hasSubmitted());
        assertEquals(7, p2.getHand().size()); // drawn replacement
        assertEquals(GamePhase.SUBMITTING, room.getPhase());

        // p2 cannot submit twice
        gameService.submitCards(p2.getSessionId(), roomId, List.of(p2.getHand().get(0).id()));
        assertEquals(1, room.getRoundSubmissions().size());

        // p3 submits valid cards -> should transition to JUDGING
        gameService.submitCards(p3.getSessionId(), roomId, p3Cards);
        assertEquals(2, room.getRoundSubmissions().size());
        assertEquals(GamePhase.JUDGING, room.getPhase());
    }

    @Test
    @DisplayName("Judge awards point, round transitions, and nextRound rotates judge")
    void testJudgingAndNextRound() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");
        gameService.startGame("session-1", roomId);

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        Player judge = room.getJudge();
        List<Player> submitters = room.getEligibleSubmitters();

        int pick = room.getCurrentPrompt().pickCount();
        for (Player p : submitters) {
            List<String> cards = p.getHand().subList(0, pick).stream().map(ResponseCard::id).toList();
            gameService.submitCards(p.getSessionId(), roomId, cards);
        }

        assertEquals(GamePhase.JUDGING, room.getPhase());
        String winningSubId = room.getRoundSubmissions().get(0).getSubmissionId();
        String winningPlayerId = room.getRoundSubmissions().get(0).getPlayerId();

        // Non-judge cannot judge
        gameService.judgeSubmission(submitters.get(0).getSessionId(), roomId, winningSubId);
        assertEquals(GamePhase.JUDGING, room.getPhase());

        // Judge judges
        gameService.judgeSubmission(judge.getSessionId(), roomId, winningSubId);
        assertEquals(GamePhase.ROUND_OVER, room.getPhase());

        Player winner = room.findPlayerById(winningPlayerId).orElseThrow();
        assertEquals(1, winner.getScore());

        // Advance to next round
        gameService.nextRound(judge.getSessionId(), roomId);
        assertEquals(GamePhase.SUBMITTING, room.getPhase());
        assertNotEquals(judge.getId(), room.getJudge().getId()); // Judge rotated
        assertFalse(submitters.get(0).hasSubmitted());
    }

    @Test
    @DisplayName("Concurrency: Simultaneous submissions by players are handled safely without race conditions")
    void testConcurrentSubmissions() throws InterruptedException {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        gameService.joinRoom("session-2", "room-id-doesnt-matter", "Bob"); // testing proper join with roomId
        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");
        gameService.joinRoom("session-4", roomId, "Dave");
        gameService.startGame("session-1", roomId);

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        List<Player> submitters = room.getEligibleSubmitters();

        int pick = room.getCurrentPrompt().pickCount();
        int threads = submitters.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (Player p : submitters) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    List<String> cards = p.getHand().subList(0, pick).stream().map(ResponseCard::id).toList();
                    gameService.submitCards(p.getSessionId(), roomId, cards);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // All submitters submitted, phase should be JUDGING
        assertEquals(submitters.size(), room.getRoundSubmissions().size());
        assertEquals(GamePhase.JUDGING, room.getPhase());
    }

    @Test
    @DisplayName("Disconnect handling: preserves room state and marks disconnected for reconnect")
    void testDisconnectHandling() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        gameService.joinRoom("session-2", roomId, "Bob");

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        assertEquals(2, room.getPlayers().size());

        // Host disconnects -> Alice marked disconnected, remains in room for reconnect
        gameService.handleDisconnect("session-1");
        assertEquals(2, room.getPlayers().size());
        assertFalse(room.getPlayers().get(0).isConnected());
        assertTrue(room.getPlayers().get(0).isHost());

        // Alice reconnects with new session
        gameService.joinRoom("session-3", roomId, "Alice");
        assertTrue(room.getPlayers().get(0).isConnected());
    }

    @Test
    @DisplayName("Win condition transitions to GAME_OVER when player reaches winning score")
    void testGameOverCondition() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        gameService.joinRoom("session-2", roomId, "Bob");
        gameService.joinRoom("session-3", roomId, "Charlie");

        GameRoom room = roomManager.getRoom(roomId).orElseThrow();
        room.setWinningScore(1); // Set winning threshold to 1 for test
        gameService.startGame("session-1", roomId);

        Player judge = room.getJudge();
        List<Player> submitters = room.getEligibleSubmitters();
        int pick = room.getCurrentPrompt().pickCount();
        for (Player p : submitters) {
            List<String> cards = p.getHand().subList(0, pick).stream().map(ResponseCard::id).toList();
            gameService.submitCards(p.getSessionId(), roomId, cards);
        }

        assertEquals(GamePhase.JUDGING, room.getPhase());
        String winningSubId = room.getRoundSubmissions().get(0).getSubmissionId();
        gameService.judgeSubmission(judge.getSessionId(), roomId, winningSubId);

        assertEquals(GamePhase.GAME_OVER, room.getPhase());
        assertNotNull(room.getWinningPlayerId());
    }

    @Test
    @DisplayName("Deck recycling works seamlessly when response cards run out")
    void testDeckRecycling() {
        gameService.createRoom("session-1", "Alice");
        String roomId = sessionRegistry.resolve("session-1").get().roomId();
        GameRoom room = roomManager.getRoom(roomId).orElseThrow();

        // Initialize with small card sets
        List<PromptCard> prompts = List.of(new PromptCard("p1", "Test?", 1));
        List<ResponseCard> responses = List.of(
                new ResponseCard("r1", "A"),
                new ResponseCard("r2", "B")
        );
        room.initializeDecks(prompts, responses);

        // Draw 2 responses -> deck empty
        List<ResponseCard> drawn1 = room.drawResponses(2);
        assertEquals(2, drawn1.size());

        // Draw more -> empty
        List<ResponseCard> drawn2 = room.drawResponses(1);
        assertEquals(0, drawn2.size());

        // Discard drawn cards
        room.discardResponses(drawn1);

        // Draw again -> should recycle discarded cards
        List<ResponseCard> drawn3 = room.drawResponses(2);
        assertEquals(2, drawn3.size());
    }
}
