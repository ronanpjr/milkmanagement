package com.erp.ocr.exception;

import java.time.Instant;

/**
 * Cota diária de uploads de OCR esgotada para o usuário autenticado.
 * <p>
 * O reset é a próxima meia-noite no fuso configurado em {@code ocr.quota.zone}
 * — não um intervalo móvel —, porque a cota é contada por dia civil.
 */
public class OcrQuotaExceededException extends TooManyRequestsException {

    public static final String SCOPE = "ocr-quota";

    public OcrQuotaExceededException(int limit, Instant resetInstant, long retryAfterSeconds) {
        super("Cota diária de OCR esgotada (limite de " + limit
                        + " uploads por dia). Tente novamente após o horário indicado em X-Ocr-Quota-Reset.",
                SCOPE, retryAfterSeconds, limit, resetInstant);
    }
}
