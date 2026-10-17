package com.cardgame.service;

import com.cardgame.model.GameRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe room registry using ConcurrentHashMap.
 * Handles room creation, lookup, removal, and expiration of abandoned rooms.
 * Room-level operations are thread-safe at the map level;
 * GameRoom mutations still require synchronized(room) in GameService.
 */
@Service
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);
    private static final String ROOM_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No 0/O/1/I
    private static final int ROOM_ID_LENGTH = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    @Value("${game.max-rooms:100}")
    private int maxRooms = 100;

    @Value("${game.room-expiry-minutes:60}")
    private int roomExpiryMinutes = 60;

    /**
     * Creates a new room with a unique ID.
     * @throws IllegalStateException if max room limit is reached
     */
    public GameRoom createRoom() {
        if (rooms.size() >= maxRooms) {
            throw new IllegalStateException("Maximum number of rooms reached (" + maxRooms + ")");
        }
        String roomId = generateUniqueRoomId();
        GameRoom room = new GameRoom(roomId);
        rooms.put(roomId, room);
        log.info("Room created: {}", roomId);
        return room;
    }

    public Optional<GameRoom> getRoom(String roomId) {
        if (roomId == null) return Optional.empty();
        return Optional.ofNullable(rooms.get(roomId.toUpperCase()));
    }

    public void removeRoom(String roomId) {
        GameRoom removed = rooms.remove(roomId);
        if (removed != null) {
            log.info("Room removed: {}", roomId);
        }
    }

    public int getRoomCount() {
        return rooms.size();
    }

    /**
     * Scheduled cleanup of expired rooms every 5 minutes.
     * Uses a reasonable interval rather than aggressive polling.
     */
    @Scheduled(fixedDelayString = "${game.cleanup-interval-ms:300000}")
    void cleanupExpiredRooms() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(roomExpiryMinutes));
        rooms.forEach((id, room) -> {
            if (room.getLastActivity().isBefore(cutoff)) {
                rooms.remove(id);
                log.info("Expired and removed room: {}", id);
            }
        });
    }

    private String generateUniqueRoomId() {
        for (int attempts = 0; attempts < 100; attempts++) {
            String id = generateRoomId();
            if (!rooms.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique room ID after 100 attempts");
    }

    private String generateRoomId() {
        StringBuilder sb = new StringBuilder(ROOM_ID_LENGTH);
        for (int i = 0; i < ROOM_ID_LENGTH; i++) {
            sb.append(ROOM_ID_CHARS.charAt(RANDOM.nextInt(ROOM_ID_CHARS.length())));
        }
        return sb.toString();
    }
}
