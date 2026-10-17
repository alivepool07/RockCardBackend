package com.cardgame.dto;

import com.cardgame.model.ResponseCard;
import java.util.List;

/**
 * Private state sent ONLY to a specific player via their personal topic.
 * Contains their hand and submission status.
 */
public record PrivatePlayerStateDTO(
        String playerId,
        List<ResponseCard> hand,
        boolean hasSubmitted,
        boolean isJudge
) {
}
