package com.cardgame.dto;

import java.util.List;

/**
 * Client-to-server message payloads matching specification.
 */
public final class GameMessages {

    private GameMessages() {}

    public record CreateRoomRequest(String username) {}

    public record JoinRoomRequest(String roomId, String username) {}

    public record StartGameRequest(String roomId, String playerId, Integer maxRounds, Integer timerSeconds) {}

    public record SubmitCardsRequest(String roomId, String playerId, List<String> cardIds) {}

    public record JudgeRequest(String roomId, String playerId, String judgeId, String winningSubmissionId) {}

    public record NextRoundRequest(String roomId, String playerId) {}

    /** Generic error response sent to a single client */
    public record ErrorResponse(String error, String roomId) {}
}
