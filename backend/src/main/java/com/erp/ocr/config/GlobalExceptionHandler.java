package com.erp.ocr.config;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.erp.ocr.dto.ApiErrorDto;
import com.erp.ocr.exception.OcrProviderException;
import com.erp.ocr.exception.TooManyRequestsException;

import java.util.stream.Collectors;

/**
 * Tratamento global de exceções, padronizando o corpo de erro retornado pela
 * API e evitando o vazamento de stack traces para o cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorDto> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<ApiErrorDto> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDto> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Erro de validação nos dados enviados.";
        }
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorDto> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(this::formatConstraintViolation)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Erro de validação nos dados enviados.";
        }
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorDto> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        logger.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "A operação viola uma restrição de integridade dos dados (registro duplicado ou relacionado).",
                request);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiErrorDto> handleConcurrencyFailure(ConcurrencyFailureException ex,
            HttpServletRequest request) {
        // CockroachDB is SERIALIZABLE-only: a concurrent check-then-insert race (e.g.
        // two overlapping fechamentos created at once) is rejected as a
        // serialization/retry conflict rather than a unique-constraint violation.
        // Spring's SQL exception translation surfaces that as a
        // ConcurrencyFailureException (e.g. CannotAcquireLockException) - treat it the
        // same way as a data-integrity conflict from the client's point of view.
        logger.warn("Concurrency/serialization conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "A operação conflitou com outra alteração concorrente. Tente novamente.", request);
    }

    /**
     * Rate limiting e cota de OCR (B5): 429 com o contrato que o frontend
     * espera — {@code Retry-After} em segundos, limites em {@code X-RateLimit-*}
     * e, quando o escopo é OCR, os mesmos valores em {@code X-Ocr-Quota-*} com
     * o reset em ISO-8601. Handler próprio para não cair no 500 genérico.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiErrorDto> handleTooManyRequests(TooManyRequestsException ex,
            HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        headers.set("X-RateLimit-Limit", Integer.toString(ex.getLimit()));
        headers.set("X-RateLimit-Remaining", Integer.toString(ex.getRemaining()));
        if (ex.getResetInstant() != null) {
            headers.set("X-RateLimit-Reset", Long.toString(ex.getResetInstant().getEpochSecond()));
            if (ex.getScope() != null && ex.getScope().startsWith("ocr")) {
                headers.set("X-Ocr-Quota-Limit", Integer.toString(ex.getLimit()));
                headers.set("X-Ocr-Quota-Remaining", Integer.toString(ex.getRemaining()));
                headers.set("X-Ocr-Quota-Reset", ex.getResetInstant().toString());
            }
        }

        ApiErrorDto body = ApiErrorDto.of(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(body);
    }

    /**
     * Falha do provedor externo de OCR. Um 402/429/503 do Gemini não é erro
     * inesperado da aplicação: vira 429 (limite) ou 503 (indisponível) com
     * mensagem acionável, em vez de 500 genérico.
     */
    @ExceptionHandler(OcrProviderException.class)
    public ResponseEntity<ApiErrorDto> handleOcrProvider(OcrProviderException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS && ex.getRetryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }

        logger.warn("Falha do provedor de OCR em {}: status={} upstream={}", request.getRequestURI(),
                ex.getStatus().value(), ex.getUpstreamStatus());

        ApiErrorDto body = ApiErrorDto.of(ex.getMessage(), ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(), request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).headers(headers).body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorDto> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        // Cobre /api/auth/login (credenciais inválidas) e /api/auth/refresh
        // (refresh token expirado/inválido). A mensagem de login é genérica de
        // propósito — não revela se foi o e-mail ou a senha que estava errado.
        return buildResponse(HttpStatus.UNAUTHORIZED,
                ex.getMessage() != null ? ex.getMessage() : "Email ou senha inválidos.",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorDto> handleAuthenticationException(AuthenticationException ex,
            HttpServletRequest request) {
        // AuthController.login authenticates manually (the endpoint itself is
        // permitAll so Spring Security's filter chain never intercepts it), so a bad
        // credentials failure surfaces here as a plain thrown exception. Without this
        // handler it would previously fall through to the generic 500 handler below.
        return buildResponse(HttpStatus.UNAUTHORIZED, "Email ou senha inválidos.", request);
    }

    /**
     * Rota inexistente. Antes caía no handler genérico e o cliente recebia 500
     * para um simples recurso que não existe; agora o status é o 404 correto,
     * ainda no corpo padrão {@link ApiErrorDto}.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorDto> handleNoResourceFound(NoResourceFoundException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "Recurso não encontrado.", request);
    }

    /**
     * Método HTTP errado para uma rota existente (ex.: POST em rota GET).
     * Antes virava 500; agora 405.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "Método HTTP não suportado para este recurso.", request);
    }

    /**
     * Content-Type não suportado (ex.: POST sem {@code application/json}).
     * Antes virava 500; agora 415.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Tipo de conteúdo (Content-Type) não suportado.", request);
    }

    /**
     * Erros de binding do MVC: parâmetro ausente ou com tipo inválido (ex.:
     * {@code id} não numérico, {@code data} fora do formato ISO). São erro do
     * cliente, não do servidor — 400, não 500.
     */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<ApiErrorDto> handleBadRequestBinding(Exception ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Parâmetro de requisição inválido.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDto> handleGenericException(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro inesperado. Tente novamente mais tarde.", request);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private ResponseEntity<ApiErrorDto> buildResponse(HttpStatus status, String message, HttpServletRequest request) {
        ApiErrorDto body = ApiErrorDto.of(message, status.value(), status.getReasonPhrase(),
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
