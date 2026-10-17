package com.cardgame.dto;

import com.cardgame.model.ResponseCard;
import java.util.List;

/**
 * Public view of a player — visible to all room members.
 * Never contains hand contents.
 */
public record PlayerDTO(
        String id,
        String username,
        int score,
        boolean isHost,
        boolean hasSubmitted,
        boolean isJudge,
        boolean isConnected,
        int handSize
) {
}
