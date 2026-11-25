package com.erp.ocr.exception;

import java.time.Instant;

/**
 * Rate limit de janela fixa excedido para um escopo (OCR, lote, ...).
 * <p>
 * O {@code scope} identifica qual limite estourou; a mensagem é montada a
 * partir dele para o usuário final entender o que precisa esperar.
 */
public class RateLimitExceededException extends TooManyRequestsException {

    public static final String SCOPE_OCR = "ocr-rate";
    public static final String SCOPE_LOTE = "lote-rate";

    public RateLimitExceededException(String scope, int limit, Instant resetInstant, long retryAfterSeconds) {
        super(mensagem(scope, limit), scope, retryAfterSeconds, limit, resetInstant);
    }

    private static String mensagem(String scope, int limit) {
        if (SCOPE_OCR.equals(scope)) {
            return "Muitas solicitações de OCR (limite de " + limit
                    + " por janela). Aguarde o intervalo indicado em Retry-After.";
        }
        if (SCOPE_LOTE.equals(scope)) {
            return "Muitos lançamentos em lote (limite de " + limit
                    + " por janela). Aguarde o intervalo indicado em Retry-After.";
        }
        return "Muitas requisições (limite de " + limit
                + " por janela). Aguarde o intervalo indicado em Retry-After.";
    }
}
