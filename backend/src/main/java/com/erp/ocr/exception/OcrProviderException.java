package com.erp.ocr.exception;

import org.springframework.http.HttpStatus;

/**
 * Falha do provedor externo de OCR (Vertex AI/Gemini).
 * <p>
 * Existe para separar "o provedor está fora, limitado ou sem créditos" de
 * "erro inesperado na aplicação". Sem isso, um 402/429/503 do Gemini virava
 * HTTP 500 no {@code GlobalExceptionHandler}, escondendo do cliente que o
 * problema é temporário e que vale a pena tentar de novo mais tarde.
 */
public class OcrProviderException extends RuntimeException {

    private final HttpStatus status;
    private final Integer upstreamStatus;
    private final long retryAfterSeconds;
    private final boolean fallbackEligible;

    private OcrProviderException(HttpStatus status, String message, Integer upstreamStatus,
            long retryAfterSeconds, boolean fallbackEligible, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.upstreamStatus = upstreamStatus;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        this.fallbackEligible = fallbackEligible;
    }

    public static OcrProviderException rateLimited(long retryAfterSeconds, Throwable cause) {
        return new OcrProviderException(HttpStatus.TOO_MANY_REQUESTS,
                "O provedor de OCR está temporariamente limitado. Tente novamente em alguns instantes.",
                429, retryAfterSeconds, true, cause);
    }

    public static OcrProviderException unavailable(int upstreamStatus, Throwable cause) {
        return new OcrProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                "O provedor de OCR está temporariamente indisponível. Tente novamente mais tarde.",
                upstreamStatus, 0L, true, cause);
    }

    /**
     * Credencial inválida, permissão ausente ou créditos esgotados. Não adianta
     * trocar de modelo: o problema é da conta/projeto.
     */
    public static OcrProviderException configuration(Integer upstreamStatus, Throwable cause) {
        return new OcrProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                "O provedor de OCR não está configurado corretamente. Verifique as credenciais e os créditos do projeto.",
                upstreamStatus, 0L, false, cause);
    }

    public static OcrProviderException modelUnavailable(String model, Throwable cause) {
        return new OcrProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                "O modelo de OCR configurado não está disponível. Tente novamente mais tarde.",
                404, 0L, true, cause);
    }

    public static OcrProviderException network(Throwable cause) {
        return new OcrProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível falar com o provedor de OCR. Tente novamente mais tarde.",
                null, 0L, true, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isFallbackEligible() {
        return fallbackEligible;
    }
}
