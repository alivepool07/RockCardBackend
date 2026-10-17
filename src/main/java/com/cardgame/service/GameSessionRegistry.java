package com.cardgame.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks WebSocket session → (roomId, playerId) associations.
 * This is the authoritative source for resolving who a WebSocket session belongs to.
 * The server never trusts client-supplied player IDs; it resolves identity from the session.
 */
@Service
public class GameSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GameSessionRegistry.class);

    public record SessionMapping(String roomId, String playerId) {}

    private final ConcurrentHashMap<String, SessionMapping> sessionToPlayer = new ConcurrentHashMap<>();

    /**
     * Registers a session → player mapping.
     */
    public void register(String sessionId, String roomId, String playerId) {
        sessionToPlayer.put(sessionId, new SessionMapping(roomId, playerId));
        log.debug("Registered session {} → room={}, player={}", sessionId, roomId, playerId);
    }

    /**
     * Unregisters a session mapping.
     * @return the mapping that was removed, or empty if not found
     */
    public Optional<SessionMapping> unregister(String sessionId) {
        SessionMapping removed = sessionToPlayer.remove(sessionId);
        if (removed != null) {
            log.debug("Unregistered session {} (room={}, player={})",
                    sessionId, removed.roomId(), removed.playerId());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Resolves the player identity for a WebSocket session.
     */
    public Optional<SessionMapping> resolve(String sessionId) {
        return Optional.ofNullable(sessionToPlayer.get(sessionId));
    }

    /**
     * Checks if a session is already registered to any room.
     */
    public boolean isRegistered(String sessionId) {
        return sessionToPlayer.containsKey(sessionId);
    }

    /**
     * Gets the count of active session mappings.
     */
    public int getActiveSessionCount() {
        return sessionToPlayer.size();
    }
}
