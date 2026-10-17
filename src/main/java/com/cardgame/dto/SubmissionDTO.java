package com.cardgame.dto;

import com.cardgame.model.ResponseCard;
import java.util.List;

/**
 * Anonymous submission view — shown to the judge during JUDGING phase.
 * Does NOT include playerId.
 */
public record SubmissionDTO(
        String submissionId,
        List<ResponseCard> cards
) {
}
