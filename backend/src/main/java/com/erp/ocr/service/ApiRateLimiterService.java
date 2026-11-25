package com.erp.ocr.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.erp.ocr.exception.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter genérico de janela fixa, em memória, para os endpoints caros
 * (upload de OCR e lançamento em lote).
 * <p>
 * Substitui um contador por chave (escopo + usuário): a janela começa na
 * primeira requisição e expira depois de {@code janela}; atingido o limite, as
 * requisições seguintes na mesma janela recebem 429 até o reset.
 * <p>
 * <b>Escala horizontal:</b> assim como o {@link LoginRateLimiterService}, este
 * estado é local ao processo. Com mais de uma instância, cada uma teria seu
 * próprio contador e o limite efetivo seria N vezes o configurado; nesse
 * cenário o estado precisa migrar para um store compartilhado (Redis, banco).
 * A aplicação atual roda em uma única instância, então isso é aceitável.
 * <p>
 * O mapa tem teto de {@link #MAX_ENTRIES}: sem ele, chaves controladas pelo
 * cliente (usuário, IP) seriam um vetor de exaustão de memória.
 */
@Service
public class ApiRateLimiterService {

    public static final int MAX_ENTRIES = 10_000;

    private record Janela(AtomicInteger contagem, AtomicLong inicioMillis, long duracaoMillis) {
    }

    private final ConcurrentHashMap<String, Janela> janelasPorChave = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public ApiRateLimiterService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Consome uma requisição da chave no escopo informado.
     *
     * @param escopo identificador do limite (ex.: {@code ocr}, {@code lote})
     * @param chave  identidade limitada (id do usuário, e-mail ou IP)
     * @param limite máximo de requisições por janela; {@code <= 0} desabilita
     * @param janela duração da janela fixa; {@code <= 0} desabilita
     * @throws RateLimitExceededException quando o limite da janela foi atingido
     */
    public void consumir(String escopo, String chave, int limite, Duration janela) {
        if (limite <= 0 || janela == null || janela.isZero() || janela.isNegative()) {
            return;
        }

        String chaveComposta = escopo + "|" + chave;
        long agora = clock.millis();
        long duracaoMillis = janela.toMillis();

        Janela atual = janelasPorChave.compute(chaveComposta, (k, existente) -> {
            if (existente == null || expirada(existente, agora)) {
                return new Janela(new AtomicInteger(1), new AtomicLong(agora), duracaoMillis);
            }
            existente.contagem().incrementAndGet();
            return existente;
        });

        enforceEntryCap(agora);

        if (atual.contagem().get() > limite) {
            Instant reset = Instant.ofEpochMilli(atual.inicioMillis().get() + atual.duracaoMillis());
            long retryAfter = Math.max(1L,
                    (atual.duracaoMillis() - (agora - atual.inicioMillis().get()) + 999) / 1000);
            throw new RateLimitExceededException(escopoDaExcecao(escopo), limite, reset, retryAfter);
        }
    }

    /** Remove entradas com janela expirada. Package-private para teste. */
    void purgeExpired() {
        long agora = clock.millis();
        janelasPorChave.entrySet().removeIf(entry -> expirada(entry.getValue(), agora));
    }

    /** Número de chaves atualmente no mapa. Package-private para teste. */
    int totalEntradas() {
        return janelasPorChave.size();
    }

    private void enforceEntryCap(long agora) {
        if (janelasPorChave.size() <= MAX_ENTRIES) {
            return;
        }
        janelasPorChave.entrySet().removeIf(entry -> expirada(entry.getValue(), agora));
        int excesso = janelasPorChave.size() - MAX_ENTRIES;
        if (excesso <= 0) {
            return;
        }
        List<String> maisAntigas = janelasPorChave.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().inicioMillis().get()))
                .limit(excesso)
                .map(Map.Entry::getKey)
                .toList();
        for (String chave : maisAntigas) {
            janelasPorChave.remove(chave);
        }
    }

    /**
     * O escopo usado na chave do mapa é curto ("ocr", "lote"); o contrato de
     * erro usa o nome completo ("ocr-rate", "lote-rate"), como o frontend
     * espera.
     */
    private String escopoDaExcecao(String escopo) {
        if ("ocr".equals(escopo)) {
            return RateLimitExceededException.SCOPE_OCR;
        }
        if ("lote".equals(escopo)) {
            return RateLimitExceededException.SCOPE_LOTE;
        }
        return escopo;
    }

    private boolean expirada(Janela janela, long agora) {
        return agora - janela.inicioMillis().get() > janela.duracaoMillis();
    }
}
