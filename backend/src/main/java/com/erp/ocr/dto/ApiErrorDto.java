package com.erp.ocr.dto;

import java.time.Instant;

/**
 * Formato padrão de resposta de erro da API.
 */
public record ApiErrorDto(
        String message,
        int status,
        String error,
        Instant timestamp,
        String path) {

    public static ApiErrorDto of(String message, int status, String error, String path) {
        return new ApiErrorDto(message, status, error, Instant.now(), path);
    }
}
