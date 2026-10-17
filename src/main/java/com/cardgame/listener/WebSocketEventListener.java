package com.cardgame.listener;

import com.cardgame.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Listens for WebSocket disconnect events and delegates cleanup to GameService.
 * Handles duplicate disconnect events safely (GameService is idempotent on unknown sessions).
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final GameService gameService;

    public WebSocketEventListener(GameService gameService) {
        this.gameService = gameService;
    }

    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userKey = accessor.getUser() != null ? accessor.getUser().getName() : accessor.getSessionId();
        if (userKey != null) {
            log.info("WebSocket session disconnected: {}", userKey);
            gameService.handleDisconnect(userKey);
        }
        if (accessor.getSessionId() != null && !accessor.getSessionId().equals(userKey)) {
            gameService.handleDisconnect(accessor.getSessionId());
        }
    }
}
