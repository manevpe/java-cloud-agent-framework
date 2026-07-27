package io.github.manevpe.agentic.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates the engine's plain Java exceptions into meaningful HTTP
 * statuses for the webhook ingress, instead of a generic 500. Kept
 * separate from {@link WorkflowWebhookController} so future controllers
 * (e.g. an approval-decision endpoint) get the same mapping for
 * free.
 */
@RestControllerAdvice
class WebhookExceptionHandler {

    /**
     * Thrown for "unknown workflow id" / "unknown correlation key" —
     * both are client errors (a mistyped URL or a stale webhook config),
     * not a caller/server fault worth a 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** Thrown for workflow/engine misconfiguration (e.g. an unresolvable agent). */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
