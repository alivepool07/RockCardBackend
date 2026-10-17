package com.cardgame.dto;

import com.cardgame.model.GamePhase;
import com.cardgame.model.PromptCard;
import java.util.List;

/**
 * Public room state broadcast to all players in a room via the room topic.
 * Contains ONLY information that every room member is allowed to see.
 * Player hands, unrevealed submission authors, and deck ordering are never included.
 */
public record RoomStateDTO(
        String roomId,
        GamePhase phase,
        List<PlayerDTO> players,
        PromptCard currentPrompt,
        String judgeName,
        String judgeId,
        int winningScore,
        int maxRounds,
        int currentRound,
        int submissionTimerSeconds,
        Long submissionDeadlineEpochMs,

        // Present only during JUDGING — anonymous submissions
        List<SubmissionDTO> submissions,

        // Present only during ROUND_OVER — revealed submissions
        List<RevealedSubmissionDTO> revealedSubmissions,
        String winningSubmissionId,
        String winningPlayerId,
        String winningPlayerName,

        // Error/info message for the room
        String message
) {
}
