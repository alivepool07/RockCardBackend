package com.cardgame.service;

import com.cardgame.model.PromptCard;
import com.cardgame.model.ResponseCard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Loads card definitions once at startup and provides immutable copies.
 * Each room gets its own shuffled copy of the shared card definitions.
 * The shared definitions are never mutated.
 */
@Service
public class DeckService {

    private static final Logger log = LoggerFactory.getLogger(DeckService.class);
    private final ObjectMapper objectMapper;

    private List<PromptCard> promptDefinitions = Collections.emptyList();
    private List<ResponseCard> responseDefinitions = Collections.emptyList();

    public DeckService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadCards() {
        promptDefinitions = loadResource("prompts.json", new TypeReference<>() {});
        responseDefinitions = loadResource("responses.json", new TypeReference<>() {});
        log.info("Loaded {} prompt cards and {} response cards",
                promptDefinitions.size(), responseDefinitions.size());
    }

    private <T> List<T> loadResource(String filename, TypeReference<List<T>> typeRef) {
        try (InputStream is = new ClassPathResource(filename).getInputStream()) {
            List<T> items = objectMapper.readValue(is, typeRef);
            return Collections.unmodifiableList(items);
        } catch (IOException e) {
            log.error("Failed to load card definitions from {}", filename, e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns a fresh, unmodifiable copy of all prompt card definitions.
     * Safe to pass to a GameRoom for deck initialization.
     */
    public List<PromptCard> getPromptDefinitions() {
        return promptDefinitions;
    }

    /**
     * Returns a fresh, unmodifiable copy of all response card definitions.
     * Safe to pass to a GameRoom for deck initialization.
     */
    public List<ResponseCard> getResponseDefinitions() {
        return responseDefinitions;
    }
}
