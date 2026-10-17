package com.cardgame.dto;

import com.cardgame.model.ResponseCard;
import java.util.List;

/**
 * Revealed submission — shown during ROUND_OVER to reveal who played what.
 * Includes playerId and playerName.
 */
public record RevealedSubmissionDTO(
        String submissionId,
        String playerId,
        String playerName,
        List<ResponseCard> cards,
        boolean isWinner
) {
}
