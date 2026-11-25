package com.erp.ocr.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B7: simple in-memory brute-force protection for POST /api/auth/login.
 * Tracks failed attempts per key (client IP) in a fixed time window; once the
 * threshold is reached the key is locked out (blocked) until the window
 * expires.
 * <p>
 * This is intentionally lightweight (no external dependency like Bucket4j)
 * and process-local: it resets on restart and isn't shared across multiple
 * app instances. That's an acceptable tradeoff for this app's current
 * single-instance deployment; if the app is ever horizontally scaled, this
 * should move to a shared store (e.g. Redis) to remain effective.
 */
@Component
public class LoginRateLimiterService {

    public static final int MAX_ATTEMPTS = 5;
    public static final long WINDOW_MILLIS = 15 * 60 * 1000L; // 15 minutes

    /**
     * Teto de entradas no mapa. As chaves vem do cliente (IP), entao sem teto o
     * mapa e um vetor de exaustao de memoria: basta variar o valor para criar
     * uma entrada nova a cada tentativa. Acima do teto, entradas expiradas sao
     * varridas primeiro e, persistindo o excesso, as janelas mais antigas sao
     * descartadas.
     */
    public static final int MAX_ENTRIES = 10_000;

    private record Attempts(AtomicInteger count, AtomicLong windowStartMillis) {
    }

    private final ConcurrentHashMap<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();

    /**
     * @return true if this key has reached the failure threshold within the
     *         current window and should be blocked (429).
     */
    public boolean isBlocked(String key) {
        Attempts attempts = attemptsByKey.get(key);
        if (attempts == null) {
            return false;
        }
        if (isWindowExpired(attempts)) {
            attemptsByKey.remove(key, attempts);
            return false;
        }
        return attempts.count().get() >= MAX_ATTEMPTS;
    }

    /**
     * Records a failed login attempt for this key.
     */
    public void registerFailure(String key) {
        attemptsByKey.compute(key, (k, existing) -> {
            if (existing == null || isWindowExpired(existing)) {
                return new Attempts(new AtomicInteger(1), new AtomicLong(System.currentTimeMillis()));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        enforceEntryCap();
    }

    /**
     * Clears any recorded failures for this key after a successful login.
     */
    public void registerSuccess(String key) {
        attemptsByKey.remove(key);
    }

    /**
     * Mantem o mapa dentro de {@link #MAX_ENTRIES}. Varre as entradas com a
     * janela expirada (nao so a chave consultada) e, se ainda assim houver
     * excesso, remove as de janela mais antiga. Package-private para teste.
     */
    void enforceEntryCap() {
        if (attemptsByKey.size() <= MAX_ENTRIES) {
            return;
        }
        purgeExpired();
        int excesso = attemptsByKey.size() - MAX_ENTRIES;
        if (excesso <= 0) {
            return;
        }
        List<String> maisAntigas = attemptsByKey.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().windowStartMillis().get()))
                .limit(excesso)
                .map(Map.Entry::getKey)
                .toList();
        for (String chave : maisAntigas) {
            attemptsByKey.remove(chave);
        }
    }

    /**
     * Remove do mapa todas as entradas cuja janela ja expirou. Package-private
     * para teste.
     */
    void purgeExpired() {
        attemptsByKey.entrySet().removeIf(entry -> isWindowExpired(entry.getValue()));
    }

    private boolean isWindowExpired(Attempts attempts) {
        return System.currentTimeMillis() - attempts.windowStartMillis().get() > WINDOW_MILLIS;
    }
}
