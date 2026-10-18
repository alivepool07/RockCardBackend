package com.cardgame;

import com.cardgame.model.GameRoom;
import com.cardgame.model.PromptCard;
import com.cardgame.model.ResponseCard;
import com.cardgame.service.DeckService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the card content library for integrity, uniqueness,
 * format correctness, and compatibility with the game engine.
 */
class CardContentValidationTest {

    private static List<PromptCard> prompts;
    private static List<ResponseCard> responses;

    @BeforeAll
    static void loadCards() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = new ClassPathResource("prompts.json").getInputStream()) {
            prompts = mapper.readValue(is, new TypeReference<>() {});
        }
        try (InputStream is = new ClassPathResource("responses.json").getInputStream()) {
            responses = mapper.readValue(is, new TypeReference<>() {});
        }
    }

    // --- Prompt card validations ---

    @Test
    void promptsLoadSuccessfully() {
        assertNotNull(prompts, "Prompts should not be null");
        assertFalse(prompts.isEmpty(), "Prompts should not be empty");
    }

    @Test
    void promptsMeetMinimumCount() {
        assertTrue(prompts.size() >= 150,
                "Should have at least 150 prompts, found: " + prompts.size());
    }

    @Test
    void promptIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        List<String> duplicates = prompts.stream()
                .map(PromptCard::id)
                .filter(id -> !ids.add(id))
                .toList();
        assertTrue(duplicates.isEmpty(),
                "Duplicate prompt IDs found: " + duplicates);
    }

    @Test
    void promptIdsAreNotEmpty() {
        prompts.forEach(p ->
                assertFalse(p.id() == null || p.id().isBlank(),
                        "Prompt ID must not be null or blank"));
    }

    @Test
    void promptTextsAreNotEmpty() {
        prompts.forEach(p ->
                assertFalse(p.text() == null || p.text().isBlank(),
                        "Prompt text must not be null or blank for ID: " + p.id()));
    }

    @Test
    void promptTextsAreUnique() {
        Set<String> texts = new HashSet<>();
        List<String> duplicates = prompts.stream()
                .map(p -> p.text().toLowerCase().trim())
                .filter(t -> !texts.add(t))
                .toList();
        assertTrue(duplicates.isEmpty(),
                "Duplicate prompt texts found: " + duplicates);
    }

    @Test
    void promptPickCountsAreValid() {
        prompts.forEach(p -> {
            assertTrue(p.pickCount() >= 1 && p.pickCount() <= 3,
                    "Pick count must be 1-3 for prompt " + p.id() + ", found: " + p.pickCount());
        });
    }

    @Test
    void promptsHavePick2Cards() {
        long pick2Count = prompts.stream().filter(p -> p.pickCount() == 2).count();
        assertTrue(pick2Count >= 5,
                "Should have at least 5 pick-2 prompts, found: " + pick2Count);
    }

    @Test
    void promptBlankSlotsMatchPickCount() {
        // For pick-2 prompts, text should contain 2 blanks (____) or structural separation
        prompts.stream()
                .filter(p -> p.pickCount() == 2)
                .forEach(p -> {
                    long blanks = countOccurrences(p.text(), "____");
                    assertTrue(blanks >= 2,
                            "Pick-2 prompt '" + p.id() + "' should have at least 2 blank slots, found " + blanks +
                            " in: " + p.text());
                });
    }

    // --- Response card validations ---

    @Test
    void responsesLoadSuccessfully() {
        assertNotNull(responses, "Responses should not be null");
        assertFalse(responses.isEmpty(), "Responses should not be empty");
    }

    @Test
    void responsesMeetMinimumCount() {
        assertTrue(responses.size() >= 400,
                "Should have at least 400 responses, found: " + responses.size());
    }

    @Test
    void responseIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        List<String> duplicates = responses.stream()
                .map(ResponseCard::id)
                .filter(id -> !ids.add(id))
                .toList();
        assertTrue(duplicates.isEmpty(),
                "Duplicate response IDs found: " + duplicates);
    }

    @Test
    void responseIdsAreNotEmpty() {
        responses.forEach(r ->
                assertFalse(r.id() == null || r.id().isBlank(),
                        "Response ID must not be null or blank"));
    }

    @Test
    void responseTextsAreNotEmpty() {
        responses.forEach(r ->
                assertFalse(r.text() == null || r.text().isBlank(),
                        "Response text must not be null or blank for ID: " + r.id()));
    }

    @Test
    void responseTextsAreUnique() {
        Set<String> texts = new HashSet<>();
        List<String> duplicates = responses.stream()
                .map(r -> r.text().toLowerCase().trim())
                .filter(t -> !texts.add(t))
                .toList();
        assertTrue(duplicates.isEmpty(),
                "Duplicate response texts found: " + duplicates);
    }

    // --- Cross-validation ---

    @Test
    void noIdCollisionsBetweenPromptsAndResponses() {
        Set<String> promptIds = prompts.stream().map(PromptCard::id).collect(Collectors.toSet());
        Set<String> responseIds = responses.stream().map(ResponseCard::id).collect(Collectors.toSet());
        Set<String> collisions = new HashSet<>(promptIds);
        collisions.retainAll(responseIds);
        // IDs use different prefixes (p vs r), so this should be empty
        assertTrue(collisions.isEmpty(),
                "ID collisions between prompts and responses: " + collisions);
    }

    // --- Game engine compatibility ---

    @Test
    void enoughResponsesForFullGame() {
        // With 20 players, hand size 7, we need 20*7 = 140 cards minimum to deal
        // Plus submissions and redraws across rounds
        int maxPlayers = 20;
        int handSize = 7;
        int minimumRequired = maxPlayers * handSize;
        assertTrue(responses.size() >= minimumRequired,
                "Need at least " + minimumRequired + " responses for max players, found: " + responses.size());
    }

    @Test
    void enoughPromptsForMaxRounds() {
        // Max rounds is typically 10, need enough prompts to avoid immediate recycling
        int maxRounds = 10;
        assertTrue(prompts.size() >= maxRounds,
                "Need at least " + maxRounds + " prompts for max rounds, found: " + prompts.size());
    }

    @Test
    void responseToPromptRatioIsHealthy() {
        // At least 2 responses per prompt for variety
        double ratio = (double) responses.size() / prompts.size();
        assertTrue(ratio >= 2.0,
                "Response:Prompt ratio should be at least 2:1, found: " + String.format("%.1f:1", ratio));
    }

    // --- Content quality checks ---

    @Test
    void promptsAreReasonablyLengthed() {
        prompts.forEach(p -> {
            assertTrue(p.text().length() >= 10,
                    "Prompt too short (< 10 chars): " + p.id() + " = '" + p.text() + "'");
            assertTrue(p.text().length() <= 300,
                    "Prompt too long (> 300 chars): " + p.id() + " = '" + p.text() + "'");
        });
    }

    @Test
    void responsesAreReasonablyLengthed() {
        responses.forEach(r -> {
            assertTrue(r.text().length() >= 5,
                    "Response too short (< 5 chars): " + r.id() + " = '" + r.text() + "'");
            assertTrue(r.text().length() <= 250,
                    "Response too long (> 250 chars): " + r.id() + " = '" + r.text() + "'");
        });
    }

    @Test
    void printCardStatistics() {
        long pick1 = prompts.stream().filter(p -> p.pickCount() == 1).count();
        long pick2 = prompts.stream().filter(p -> p.pickCount() == 2).count();
        long pick3 = prompts.stream().filter(p -> p.pickCount() == 3).count();

        System.out.println("=== Card Library Statistics ===");
        System.out.println("Total Prompts:    " + prompts.size());
        System.out.println("  Pick-1:         " + pick1);
        System.out.println("  Pick-2:         " + pick2);
        System.out.println("  Pick-3:         " + pick3);
        System.out.println("Total Responses:  " + responses.size());
        System.out.println("Ratio (R:P):      " + String.format("%.1f:1", (double) responses.size() / prompts.size()));
        System.out.println("Max game support:  " + (responses.size() / 7) + " players with full hands");
        System.out.println("===============================");
    }

    @Test
    void deckServiceDealingAndReplenishingSimulation() {
        GameRoom room = new GameRoom("TEST1");
        DeckService deckService = new DeckService(new ObjectMapper());
        try {
            var method = DeckService.class.getDeclaredMethod("loadCards");
            method.setAccessible(true);
            method.invoke(deckService);
        } catch (Exception e) {
            fail("Failed to invoke loadCards on DeckService: " + e.getMessage());
        }

        // Initialize deck for room
        room.initializeDecks(deckService.getPromptDefinitions(), deckService.getResponseDefinitions());

        // Draw 10 prompt cards
        for (int i = 0; i < 10; i++) {
            PromptCard p = room.drawPrompt();
            assertNotNull(p, "Prompt card should not be null");
            assertNotNull(p.id());
            assertNotNull(p.text());
            room.discardPrompt(p);
        }

        // Deal hands of 7 cards to 15 players
        List<List<ResponseCard>> playerHands = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            List<ResponseCard> hand = room.drawResponses(7);
            assertEquals(7, hand.size(), "Hand must contain exactly 7 cards");
            playerHands.add(hand);
        }

        // Replenish cards (draw additional responses)
        for (int i = 0; i < 15; i++) {
            List<ResponseCard> replenish = room.drawResponses(1);
            assertEquals(1, replenish.size(), "Drawn response card should not be empty");
        }
    }

    @Test
    void adultCategoriesAreWellRepresented() {
        // Hinglish / Indian Culture
        long hinglishCount = responses.stream()
                .filter(r -> r.text().matches("(?i).*(bhai|shaadi|rishta|aunty|mummy|kalesh|chai|bangalore|delhi|swiggy|zomato|arranged|kundali|baraat|gym bro|tu janta|chappal).*"))
                .count();
        assertTrue(hinglishCount >= 10, "Expected at least 10 Hinglish/Indian culture cards, found: " + hinglishCount);

        // Bedroom Chaos & Innuendo
        long bedroomCount = responses.stream()
                .filter(r -> r.text().matches("(?i).*(bedroom|hookup|naked|bed|condom|thrust|foreplay|dirty talk|orgasm|lights|underwear|undressing|nude|intimacy).*"))
                .count();
        assertTrue(bedroomCount >= 10, "Expected at least 10 bedroom chaos cards, found: " + bedroomCount);

        // Dating & Situationships
        long datingCount = responses.stream()
                .filter(r -> r.text().matches("(?i).*(date|dating|ghosted|ghosting|situationship|ex|red flag|tinder|bumble|hinge|text|breakup|therapist).*"))
                .count();
        assertTrue(datingCount >= 20, "Expected at least 20 dating/situationship cards, found: " + datingCount);
    }

    // --- Helper ---

    private static long countOccurrences(String text, String target) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
