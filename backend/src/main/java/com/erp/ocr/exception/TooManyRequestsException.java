package com.erp.ocr.exception;

import java.time.Instant;

/**
 * Base das recusas por excesso de requisições (HTTP 429).
 * <p>
 * Carrega tudo que o cliente precisa para se recuperar sem tentar de novo em
 * loop: quantos segundos esperar ({@code Retry-After}), qual é o teto, qual o
 * escopo que estourou e quando ele volta a valer.
 */
public abstract class TooManyRequestsException extends RuntimeException {

    private final String scope;
    private final long retryAfterSeconds;
    private final int limit;
    private final int remaining;
    private final Instant resetInstant;

    protected TooManyRequestsException(String message, String scope, long retryAfterSeconds, int limit,
            Instant resetInstant) {
        super(message);
        this.scope = scope;
        // Retry-After precisa ser >= 1: "0" convidaria o cliente a repetir
        // imediatamente e transformaria o limite em um loop de requisições.
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
        this.limit = limit;
        this.remaining = 0;
        this.resetInstant = resetInstant;
    }

    public String getScope() {
        return scope;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public Instant getResetInstant() {
        return resetInstant;
    }
}
