package com.erp.ocr.service;

import java.time.Instant;

/**
 * Resultado de um consumo de cota de OCR.
 *
 * @param usados  uploads já usados hoje (incluindo o atual)
 * @param limite  teto diário configurado; 0 significa cota desabilitada
 * @param reset   instante em que a cota zera (próxima meia-noite no fuso configurado)
 */
public record OcrQuotaConsumption(int usados, int limite, Instant reset) {

    /** @return quantos uploads ainda restam no dia (nunca negativo). */
    public int restantes() {
        return Math.max(0, limite - usados);
    }
}
