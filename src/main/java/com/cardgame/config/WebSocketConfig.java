package com.cardgame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic for room-wide broadcasts, /queue for private per-player messages
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        // User-specific destinations use /queue prefix with session ID
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        org.springframework.web.socket.server.support.DefaultHandshakeHandler handshakeHandler = 
                new org.springframework.web.socket.server.support.DefaultHandshakeHandler() {
            @Override
            protected java.security.Principal determineUser(
                    org.springframework.http.server.ServerHttpRequest request, 
                    org.springframework.web.socket.WebSocketHandler wsHandler, 
                    java.util.Map<String, Object> attributes) {
                String id = java.util.UUID.randomUUID().toString();
                return () -> id;
            }
        };

        registry.addEndpoint("/ws")
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOriginPatterns("*")
                .withSockJS();
        registry.addEndpoint("/ws")
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Limit inbound message size to prevent abuse (64KB should be generous)
        registration.setMessageSizeLimit(64 * 1024);
        // Limit outbound buffer to prevent memory exhaustion from slow clients
        registration.setSendBufferSizeLimit(512 * 1024);
        // Timeout for send operations
        registration.setSendTimeLimit(20 * 1000);
    }
}
