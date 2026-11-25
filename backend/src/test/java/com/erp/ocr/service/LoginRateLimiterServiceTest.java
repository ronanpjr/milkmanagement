package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobertura de {@link LoginRateLimiterService} (B7) — trava o login após 5
 * falhas na janela de 15 minutos e zera o contador no sucesso.
 * <p>
 * O serviço usa {@code System.currentTimeMillis()} direto, sem injeção de
 * {@link java.time.Clock}; para não depender de {@code Thread.sleep} nem ficar
 * instável, os cenários de "janela expirada" recuam o início da janela por
 * reflexão sobre o estado interno — determinístico e instantâneo.
 */
class LoginRateLimiterServiceTest {

    private static final String IP = "192.168.0.10";
    private static final String OUTRO_IP = "192.168.0.11";

    private LoginRateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LoginRateLimiterService();
    }

    @Test
    void isBlocked_DeveRetornarFalseParaChaveDesconhecida() {
        assertFalse(rateLimiter.isBlocked("10.0.0.1"));
    }

    @Test
    void naoDeveBloquearAntesDeAtingirOLimite() {
        for (int i = 1; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
            assertFalse(rateLimiter.isBlocked(IP), "não deveria bloquear com " + i + " falha(s)");
        }
    }

    @Test
    void deveBloquearNaQuintaFalha() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }

        assertTrue(rateLimiter.isBlocked(IP));
    }

    @Test
    void registerSuccess_DeveZerarOMarcadorAposLoginValido() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }
        assertTrue(rateLimiter.isBlocked(IP));

        rateLimiter.registerSuccess(IP);

        assertFalse(rateLimiter.isBlocked(IP));
        // O contador zerou de verdade: 4 novas falhas ainda não bloqueiam.
        for (int i = 1; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }
        assertFalse(rateLimiter.isBlocked(IP));
    }

    @Test
    void deveContarFalhasSeparadamentePorChave() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }

        assertTrue(rateLimiter.isBlocked(IP));
        assertFalse(rateLimiter.isBlocked(OUTRO_IP));

        rateLimiter.registerSuccess(OUTRO_IP);
        assertFalse(rateLimiter.isBlocked(OUTRO_IP));
    }

    @Test
    void isBlocked_DeveDesbloquearQuandoAJanelaExpira() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }
        assertTrue(rateLimiter.isBlocked(IP));

        envelhecerJanela(IP, LoginRateLimiterService.WINDOW_MILLIS + 1000);

        assertFalse(rateLimiter.isBlocked(IP));
    }

    @Test
    void isBlocked_DeveRemoverAEntradaExpiradaDoMapa() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }
        envelhecerJanela(IP, LoginRateLimiterService.WINDOW_MILLIS + 1000);

        rateLimiter.isBlocked(IP);

        assertTrue(registroDe(IP).isEmpty(), "entrada expirada deveria ter sido descartada");
    }

    @Test
    void registerFailure_DeveReiniciarOContadorQuandoAJanelaAnteriorExpirou() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS; i++) {
            rateLimiter.registerFailure(IP);
        }
        envelhecerJanela(IP, LoginRateLimiterService.WINDOW_MILLIS + 1000);

        // Nova janela: a primeira falha reinicia em 1, então 4 no total não bloqueiam.
        for (int i = 0; i < LoginRateLimiterService.MAX_ATTEMPTS - 1; i++) {
            rateLimiter.registerFailure(IP);
        }
        assertFalse(rateLimiter.isBlocked(IP));

        rateLimiter.registerFailure(IP);
        assertTrue(rateLimiter.isBlocked(IP));
    }

    @Test
    void naoDeveConsiderarJanelaExpiradaDentroDoPrazo() {
        rateLimiter.registerFailure(IP);
        envelhecerJanela(IP, LoginRateLimiterService.WINDOW_MILLIS - 60_000);

        assertFalse(rateLimiter.isBlocked(IP));
        assertEquals(1, quantidadeDeFalhas(IP));
    }

    @Test
    void deveSerEstavelComChavesDistintasEmConcorrencia() {
        int threads = 8;
        int falhasPorThread = 50;
        Runnable registrar = () -> {
            for (int i = 0; i < falhasPorThread; i++) {
                rateLimiter.registerFailure(IP);
            }
        };

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(registrar);
            workers[i].start();
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrompido");
            }
        }

        // Nenhuma falha pode se perder: 8*50 = 400 falhas, bem acima do limite.
        assertEquals(threads * falhasPorThread, quantidadeDeFalhas(IP));
        assertTrue(rateLimiter.isBlocked(IP));
    }

    // ------------------------------------------------------------------
    // Teto de entradas e varredura de expirados (achado 5)
    // ------------------------------------------------------------------

    @Test
    void purgeExpired_DeveRemoverEntradasExpiradasEManterAsVivas() {
        rateLimiter.registerFailure("expirada-1");
        rateLimiter.registerFailure("expirada-2");
        rateLimiter.registerFailure("viva");
        envelhecerJanela("expirada-1", LoginRateLimiterService.WINDOW_MILLIS + 1000);
        envelhecerJanela("expirada-2", LoginRateLimiterService.WINDOW_MILLIS + 1000);

        rateLimiter.purgeExpired();

        assertFalse(registroDe("expirada-1").containsKey("expirada-1"));
        assertFalse(registroDe("expirada-2").containsKey("expirada-2"));
        assertTrue(registroDe("viva").containsKey("viva"));
    }

    @Test
    void registerFailure_NaoDeixaOMapaCrescerAlemDoTeto() {
        int total = LoginRateLimiterService.MAX_ENTRIES + 50;
        for (int i = 0; i < total; i++) {
            rateLimiter.registerFailure("10.1." + (i / 256) + "." + (i % 256));
        }

        assertTrue(registroDe("x").size() <= LoginRateLimiterService.MAX_ENTRIES,
                "mapa passou do teto: " + registroDe("x").size());
    }

    @Test
    void enforceEntryCap_DeveDescartarAsJanelasMaisAntigasQuandoNaoHaExpiradas() {
        for (int i = 0; i < LoginRateLimiterService.MAX_ENTRIES; i++) {
            rateLimiter.registerFailure("k" + i);
        }
        // Ainda dentro da janela, mas a mais antiga do mapa.
        envelhecerJanela("k0", LoginRateLimiterService.WINDOW_MILLIS - 60_000);

        rateLimiter.registerFailure("nova");

        assertFalse(registroDe("k0").containsKey("k0"), "a janela mais antiga deveria sair");
        assertTrue(registroDe("k0").size() <= LoginRateLimiterService.MAX_ENTRIES);
    }

    // ------------------------------------------------------------------
    // Helpers de reflexão sobre o estado interno (janela de tempo)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> registroDe(String chave) {
        try {
            Field field = LoginRateLimiterService.class.getDeclaredField("attemptsByKey");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(rateLimiter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private int quantidadeDeFalhas(String chave) {
        Object attempts = registroDe(chave).get(chave);
        if (attempts == null) {
            return 0;
        }
        try {
            Method method = attempts.getClass().getDeclaredMethod("count");
            method.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicInteger) method.invoke(attempts)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Recua o início da janela da chave para {@code idadeMillis} atrás, como se
     * tivesse passado esse tempo desde a primeira tentativa.
     */
    private void envelhecerJanela(String chave, long idadeMillis) {
        Object attempts = registroDe(chave).get(chave);
        assertNotNull(attempts, "não há registro para a chave " + chave);
        try {
            Method method = attempts.getClass().getDeclaredMethod("windowStartMillis");
            method.setAccessible(true);
            ((AtomicLong) method.invoke(attempts))
                    .set(System.currentTimeMillis() - idadeMillis);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
