package com.cardgame.controller;

import com.cardgame.dto.GameMessages.*;
import com.cardgame.service.GameService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * WebSocket STOMP controller for all game actions.
 * <p>
 * Security: Player identity is NEVER taken from the message payload.
 * It is resolved from the WebSocket session via {@link com.cardgame.service.GameSessionRegistry}.
 */
@Controller
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    private String extractUserKey(SimpMessageHeaderAccessor headerAccessor) {
        if (headerAccessor.getUser() != null && headerAccessor.getUser().getName() != null) {
            return headerAccessor.getUser().getName();
        }
        return headerAccessor.getSessionId();
    }

    @MessageMapping("/room.create")
    public void createRoom(CreateRoomRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.createRoom(extractUserKey(headerAccessor), request.username());
    }

    @MessageMapping("/room.join")
    public void joinRoom(JoinRoomRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.joinRoom(extractUserKey(headerAccessor), request.roomId(), request.username());
    }

    @MessageMapping("/room.start")
    public void startGame(StartGameRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.startGame(extractUserKey(headerAccessor), request.roomId(), request.playerId(), request.maxRounds(), request.timerSeconds());
    }

    @MessageMapping("/room.submit")
    public void submitCards(SubmitCardsRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.submitCards(extractUserKey(headerAccessor), request.roomId(), request.playerId(), request.cardIds());
    }

    @MessageMapping("/room.judge")
    public void judgeSubmission(JudgeRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String judgeId = request.judgeId() != null ? request.judgeId() : request.playerId();
        gameService.judgeSubmission(extractUserKey(headerAccessor), request.roomId(), judgeId, request.winningSubmissionId());
    }

    @MessageMapping("/room.next-round")
    public void nextRound(NextRoundRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.nextRound(extractUserKey(headerAccessor), request.roomId(), request.playerId());
    }

    @MessageMapping("/room.hand")
    public void requestHand(NextRoundRequest request, SimpMessageHeaderAccessor headerAccessor) {
        gameService.sendHand(extractUserKey(headerAccessor), request.roomId(), request.playerId());
    }
}
