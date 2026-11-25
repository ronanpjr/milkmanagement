package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.erp.ocr.exception.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rate limiter de janela fixa dos endpoints caros (B5). O relógio mutável
 * permite expirar a janela sem {@code Thread.sleep}.
 */
class ApiRateLimiterServiceTest {

    private static final Instant INICIO = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration JANELA = Duration.ofMinutes(60);

    private MutableClock clock;
    private ApiRateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INICIO);
        rateLimiter = new ApiRateLimiterService(clock);
    }

    @Test
    @DisplayName("permite até o limite e bloqueia a requisição seguinte")
    void bloqueiaAcimaDoLimite() {
        rateLimiter.consumir("ocr", "usuario-1", 2, JANELA);
        rateLimiter.consumir("ocr", "usuario-1", 2, JANELA);

        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.consumir("ocr", "usuario-1", 2, JANELA));

        assertEquals(RateLimitExceededException.SCOPE_OCR, ex.getScope());
        assertEquals(2, ex.getLimit());
        assertEquals(0, ex.getRemaining());
        assertEquals(INICIO.plus(JANELA), ex.getResetInstant());
        assertEquals(3600L, ex.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("depois da janela, a chave volta a ter cota cheia")
    void janelaExpira() {
        rateLimiter.consumir("ocr", "usuario-1", 1, JANELA);

        clock.avancar(Duration.ofMinutes(61));

        assertDoesNotThrow(() -> rateLimiter.consumir("ocr", "usuario-1", 1, JANELA));
    }

    @Test
    @DisplayName("chaves e escopos diferentes têm contadores independentes")
    void chavesEEscoposIndependentes() {
        rateLimiter.consumir("ocr", "usuario-1", 1, JANELA);

        assertDoesNotThrow(() -> rateLimiter.consumir("ocr", "usuario-2", 1, JANELA));
        assertDoesNotThrow(() -> rateLimiter.consumir("lote", "usuario-1", 1, JANELA));

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.consumir("ocr", "usuario-1", 1, JANELA));
    }

    @Test
    @DisplayName("limite <= 0 desabilita o rate limit")
    void limiteZeroDesabilita() {
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> rateLimiter.consumir("ocr", "usuario-1", 0, JANELA));
        }
        assertEquals(0, rateLimiter.totalEntradas());
    }

    @Test
    @DisplayName("purgeExpired remove apenas janelas vencidas")
    void purgeRemoveExpiradas() {
        rateLimiter.consumir("ocr", "antiga", 1, JANELA);
        clock.avancar(Duration.ofMinutes(61));
        rateLimiter.consumir("ocr", "nova", 1, JANELA);

        rateLimiter.purgeExpired();

        assertEquals(1, rateLimiter.totalEntradas());
    }

    @Test
    @DisplayName("o mapa não cresce além do teto de entradas")
    void respeitaTetoDeEntradas() {
        int total = ApiRateLimiterService.MAX_ENTRIES + 50;
        for (int i = 0; i < total; i++) {
            rateLimiter.consumir("ocr", "chave-" + i, 1, JANELA);
        }

        assertTrue(rateLimiter.totalEntradas() <= ApiRateLimiterService.MAX_ENTRIES,
                "mapa passou do teto: " + rateLimiter.totalEntradas());
    }

    /** Relógio de teste que pode ser avançado explicitamente. */
    private static final class MutableClock extends Clock {

        private Instant instante;

        private MutableClock(Instant instante) {
            this.instante = instante;
        }

        private void avancar(Duration duracao) {
            instante = instante.plus(duracao);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }
}
