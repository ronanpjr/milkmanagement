package com.erp.ocr.config;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.erp.ocr.dto.ApiErrorDto;
import com.erp.ocr.exception.OcrProviderException;
import com.erp.ocr.exception.OcrQuotaExceededException;
import com.erp.ocr.exception.RateLimitExceededException;
import com.erp.ocr.util.PaginationUtil;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobertura de {@link GlobalExceptionHandler}. São as regras que decidem o
 * status HTTP e — mais importante — o que <b>não</b> vaza para o cliente:
 * mensagem interna de banco e stack trace não podem aparecer no corpo.
 */
class GlobalExceptionHandlerTest {

    private static final String URI = "/api/suppliers/42";

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", URI);
    }

    private ApiErrorDto corpo(ResponseEntity<ApiErrorDto> resposta) {
        assertNotNull(resposta.getBody());
        return resposta.getBody();
    }

    @Test
    void handleEntityNotFound_DeveResponder404ComAMensagemDoErro() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleEntityNotFound(
                new EntityNotFoundException("Supplier não encontrado"), request);

        assertEquals(HttpStatus.NOT_FOUND, resposta.getStatusCode());
        ApiErrorDto erro = corpo(resposta);
        assertEquals("Supplier não encontrado", erro.message());
        assertEquals(404, erro.status());
        assertEquals("Not Found", erro.error());
        assertEquals(URI, erro.path());
    }

    @Test
    void handleBadRequest_DeveResponder400ComAMensagemDoParametroInvalido() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.of(-1, 10, null));

        ResponseEntity<ApiErrorDto> resposta = handler.handleBadRequest(erro, request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals("O parâmetro 'page' não pode ser negativo.", corpo(resposta).message());
    }

    @Test
    void handleBadRequest_DeveMapearIllegalStatePara400() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleBadRequest(
                new IllegalStateException("Fechamento já está encerrado"), request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals(400, corpo(resposta).status());
        assertEquals("Fechamento já está encerrado", corpo(resposta).message());
    }

    @Test
    void handleMethodArgumentNotValid_DeveListarTodosOsCamposInvalidos() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "produtorRequest");
        bindingResult.addError(new FieldError("produtorRequest", "name", "não pode ser vazio"));
        bindingResult.addError(new FieldError("produtorRequest", "email", "inválido"));

        ResponseEntity<ApiErrorDto> resposta = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parametroDeExemplo(), bindingResult), request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals("name: não pode ser vazio; email: inválido", corpo(resposta).message());
    }

    @Test
    void handleMethodArgumentNotValid_DeveUsarMensagemPadraoSemErrosDeCampo() throws Exception {
        BindingResult semCampos = new BeanPropertyBindingResult(new Object(), "produtorRequest");

        ResponseEntity<ApiErrorDto> resposta = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parametroDeExemplo(), semCampos), request);

        assertEquals("Erro de validação nos dados enviados.", corpo(resposta).message());
        assertEquals(400, corpo(resposta).status());
    }

    @Test
    void handleConstraintViolation_DeveListarOCaminhoDeCadaViolacao() {
        ConstraintViolation<Object> primeira = violacaoDe("criar.ano", "deve ser maior que zero");
        ConstraintViolation<Object> segunda = violacaoDe("criar.mes", "deve estar entre 1 e 12");

        ResponseEntity<ApiErrorDto> resposta = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(primeira, segunda)), request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        String mensagem = corpo(resposta).message();
        assertTrue(mensagem.contains("criar.ano: deve ser maior que zero"), mensagem);
        assertTrue(mensagem.contains("criar.mes: deve estar entre 1 e 12"), mensagem);
    }

    @Test
    void handleConstraintViolation_DeveUsarMensagemPadraoSemViolacoes() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of()), request);

        assertEquals("Erro de validação nos dados enviados.", corpo(resposta).message());
    }

    @Test
    void handleDataIntegrityViolation_DeveResponder409SemVazarMensagemDoBanco() {
        DataIntegrityViolationException banco = new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint "
                        + "\"uk_usuarios_email\" on table SECRET_SCHEMA.usuarios]");

        ResponseEntity<ApiErrorDto> resposta = handler.handleDataIntegrityViolation(banco, request);

        assertEquals(HttpStatus.CONFLICT, resposta.getStatusCode());
        String mensagem = corpo(resposta).message();
        assertFalse(mensagem.contains("SECRET_SCHEMA"), mensagem);
        assertFalse(mensagem.contains("duplicate key"), mensagem);
        assertTrue(mensagem.contains("restrição de integridade"), mensagem);
    }

    @Test
    void handleConcurrencyFailure_DeveResponder409ComMensagemDeRetry() {
        // CockroachDB é SERIALIZABLE-only: corrida de check-then-insert chega
        // aqui como ConcurrencyFailureException, não como violação de unicidade.
        ResponseEntity<ApiErrorDto> resposta = handler.handleConcurrencyFailure(
                new ConcurrencyFailureException("restart transaction: TransactionRetryWithProtoRefreshError"),
                request);

        assertEquals(HttpStatus.CONFLICT, resposta.getStatusCode());
        assertEquals("A operação conflitou com outra alteração concorrente. Tente novamente.",
                corpo(resposta).message());
    }

    @Test
    void handleTooManyRequests_DeveResponder429ComRetryAfterEHeadersDeCota() {
        Instant reset = Instant.parse("2026-10-21T03:00:00Z");

        ResponseEntity<ApiErrorDto> resposta = handler.handleTooManyRequests(
                new OcrQuotaExceededException(50, reset, 120), request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resposta.getStatusCode());
        assertEquals("120", resposta.getHeaders().getFirst("Retry-After"));
        assertEquals("50", resposta.getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("0", resposta.getHeaders().getFirst("X-RateLimit-Remaining"));
        assertEquals(String.valueOf(reset.getEpochSecond()), resposta.getHeaders().getFirst("X-RateLimit-Reset"));
        assertEquals("50", resposta.getHeaders().getFirst("X-Ocr-Quota-Limit"));
        assertEquals("0", resposta.getHeaders().getFirst("X-Ocr-Quota-Remaining"));
        assertEquals("2026-10-21T03:00:00Z", resposta.getHeaders().getFirst("X-Ocr-Quota-Reset"));
        assertEquals(429, corpo(resposta).status());
        assertTrue(corpo(resposta).message().contains("Cota diária de OCR"));
    }

    @Test
    void handleTooManyRequests_DeRateLimitDoLoteNaoEmiteHeadersDeOcr() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleTooManyRequests(
                new RateLimitExceededException(RateLimitExceededException.SCOPE_LOTE, 120,
                        Instant.parse("2026-10-20T13:00:00Z"), 30),
                request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resposta.getStatusCode());
        assertEquals("30", resposta.getHeaders().getFirst("Retry-After"));
        assertNull(resposta.getHeaders().getFirst("X-Ocr-Quota-Limit"));
        assertTrue(corpo(resposta).message().contains("lote"));
    }

    @Test
    void handleOcrProvider_RateLimitVira429ComRetryAfter() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleOcrProvider(
                OcrProviderException.rateLimited(60, null), request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resposta.getStatusCode());
        assertEquals("60", resposta.getHeaders().getFirst("Retry-After"));
        assertEquals(429, corpo(resposta).status());
        assertTrue(corpo(resposta).message().contains("temporariamente limitado"));
    }

    @Test
    void handleOcrProvider_IndisponivelVira503SemVazarDetalheInterno() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleOcrProvider(
                OcrProviderException.unavailable(503, null), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resposta.getStatusCode());
        assertEquals(503, corpo(resposta).status());
        assertNull(resposta.getHeaders().getFirst("Retry-After"));
        assertEquals("Service Unavailable", corpo(resposta).error());
        assertTrue(corpo(resposta).message().contains("temporariamente indisponível"));
    }

    @Test
    void handleOcrProvider_SemCreditosVira503ComMensagemAcionavel() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleOcrProvider(
                OcrProviderException.configuration(402, null), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resposta.getStatusCode());
        assertTrue(corpo(resposta).message().contains("créditos"));
    }

    @Test
    void handleBadCredentials_DeveResponder401PreservandoAMensagem() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleBadCredentials(
                new BadCredentialsException("Refresh token expirado"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, resposta.getStatusCode());
        assertEquals("Refresh token expirado", corpo(resposta).message());
    }

    @Test
    void handleBadCredentials_DeveUsarMensagemGenericaQuandoNaoHaMensagem() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleBadCredentials(
                new BadCredentialsException(null), request);

        assertEquals(401, corpo(resposta).status());
        assertEquals("Email ou senha inválidos.", corpo(resposta).message());
    }

    @Test
    void handleAuthenticationException_DeveResponder401ComMensagemGenerica() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleAuthenticationException(
                new InsufficientAuthenticationException("Full authentication is required"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, resposta.getStatusCode());
        // Genérica de propósito: não diz se foi e-mail ou senha.
        assertEquals("Email ou senha inválidos.", corpo(resposta).message());
    }

    @Test
    void handleNoResourceFound_DeveResponder404Enao500() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/rota-inexistente"), request);

        assertEquals(HttpStatus.NOT_FOUND, resposta.getStatusCode());
        assertEquals(404, corpo(resposta).status());
        assertEquals("Recurso não encontrado.", corpo(resposta).message());
    }

    @Test
    void handleMethodNotSupported_DeveResponder405() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"), request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resposta.getStatusCode());
        assertEquals(405, corpo(resposta).status());
        assertEquals("Método HTTP não suportado para este recurso.", corpo(resposta).message());
    }

    @Test
    void handleMediaTypeNotSupported_DeveResponder415() {
        ResponseEntity<ApiErrorDto> resposta = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain"), request);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resposta.getStatusCode());
        assertEquals(415, corpo(resposta).status());
    }

    @Test
    void handleBadRequestBinding_DeveMapearTipoInvalidoPara400() throws Exception {
        MethodArgumentTypeMismatchException tipoInvalido = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parametroDeExemplo(), null);

        ResponseEntity<ApiErrorDto> resposta = handler.handleBadRequestBinding(tipoInvalido, request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals(400, corpo(resposta).status());
        assertEquals("Parâmetro de requisição inválido.", corpo(resposta).message());
    }

    @Test
    void handleBadRequestBinding_DeveMapearParametroAusentePara400() {        ResponseEntity<ApiErrorDto> resposta = handler.handleBadRequestBinding(
                new MissingServletRequestParameterException("inicio", "java.time.LocalDate"), request);

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals(400, corpo(resposta).status());
    }

    @Test
    void handleGenericException_DeveResponder500SemVazarMensagemInterna() {        ResponseEntity<ApiErrorDto> resposta = handler.handleGenericException(
                new IllegalStateException("connection pool exhausted jdbc:mysql://10.0.0.5:3306/serra"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resposta.getStatusCode());
        String mensagem = corpo(resposta).message();
        assertEquals("Ocorreu um erro inesperado. Tente novamente mais tarde.", mensagem);
        assertFalse(mensagem.contains("jdbc"), mensagem);
        assertFalse(mensagem.contains("10.0.0.5"), mensagem);
    }

    @Test
    void erroPadronizado_DeveSempreTrazerTimestampEPath() {
        Instant antes = Instant.now();
        ApiErrorDto erro = corpo(handler.handleEntityNotFound(new EntityNotFoundException("x"), request));

        assertEquals(URI, erro.path());
        assertNotNull(erro.timestamp());
        assertFalse(erro.timestamp().isBefore(antes));
        assertTrue(Duration.between(antes, erro.timestamp()).compareTo(Duration.ofMinutes(1)) < 0);
    }

    private static MethodParameter parametroDeExemplo() throws NoSuchMethodException {
        Method metodo = GlobalExceptionHandlerTest.class.getDeclaredMethod("alvo", String.class);
        return new MethodParameter(metodo, 0);
    }

    @SuppressWarnings("unused")
    private void alvo(String valor) {
        // Só existe para dar um MethodParameter real ao MethodArgumentNotValidException.
    }

    @SuppressWarnings("unchecked")
    private static ConstraintViolation<Object> violacaoDe(String caminho, String mensagem) {
        ConstraintViolation<Object> violacao = Mockito.mock(ConstraintViolation.class);
        Mockito.when(violacao.getPropertyPath()).thenReturn(new CaminhoDeTeste(caminho));
        Mockito.when(violacao.getMessage()).thenReturn(mensagem);
        return violacao;
    }

    /**
     * O handler só usa o {@code toString()} do Path para montar a mensagem.
     */
    private record CaminhoDeTeste(String caminho) implements Path {
        @Override
        public String toString() {
            return caminho;
        }

        @Override
        public java.util.Iterator<Path.Node> iterator() {
            return java.util.Collections.emptyIterator();
        }
    }
}
