package com.erp.ocr.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.erp.ocr.config.GlobalExceptionHandler;
import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.exception.OcrProviderException;
import com.erp.ocr.exception.OcrQuotaExceededException;
import com.erp.ocr.exception.RateLimitExceededException;
import com.erp.ocr.model.User;
import com.erp.ocr.service.ApiRateLimiterService;
import com.erp.ocr.service.CollectionEntryService;
import com.erp.ocr.service.OcrQuotaConsumption;
import com.erp.ocr.service.OcrQuotaService;
import com.erp.ocr.service.ocr.OcrService;
import com.erp.ocr.service.ocr.OcrServiceFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contrato HTTP do B5: 429 com {@code Retry-After} e corpo {@code ApiErrorDto},
 * e os headers de cota no sucesso do upload de OCR. Standalone para exercitar
 * controller + {@link GlobalExceptionHandler} sem subir o contexto inteiro.
 */
class CollectionEntryControllerOcrQuotaTest {

    private static final String RESET_ISO = "2026-10-21T03:00:00Z";
    private static final Instant RESET = Instant.parse(RESET_ISO);
    private static final Long USUARIO_ID = 7L;

    private MockMvc mockMvc;
    private ApiRateLimiterService rateLimiter;
    private OcrQuotaService quotaService;
    private CollectionEntryService coletaService;
    private OcrServiceFactory ocrServiceFactory;
    private OcrService ocrService;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(ApiRateLimiterService.class);
        quotaService = mock(OcrQuotaService.class);
        coletaService = mock(CollectionEntryService.class);
        ocrServiceFactory = mock(OcrServiceFactory.class);
        ocrService = mock(OcrService.class);

        CollectionEntryController controller = new CollectionEntryController();
        ReflectionTestUtils.setField(controller, "coletaService", coletaService);
        ReflectionTestUtils.setField(controller, "apiRateLimiterService", rateLimiter);
        ReflectionTestUtils.setField(controller, "ocrQuotaService", quotaService);
        ReflectionTestUtils.setField(controller, "organizacaoContexto", new OrganizationContext());
        ReflectionTestUtils.setField(controller, "ocrServiceFactory", ocrServiceFactory);
        ReflectionTestUtils.setField(controller, "ocrRateLimit", 12);
        ReflectionTestUtils.setField(controller, "ocrRateWindowMinutes", 60L);
        ReflectionTestUtils.setField(controller, "loteRateLimit", 120);
        ReflectionTestUtils.setField(controller, "loteRateWindowMinutes", 60L);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        User usuario = new User();
        usuario.setId(USUARIO_ID);
        usuario.setEmail("operador@erpocr.test");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "coletas.pdf", "application/pdf",
                "%PDF-1.4\n% conteudo minimo\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("rate limit de OCR devolve 429 com Retry-After, ApiErrorDto e headers de cota")
    void rateLimitDeOcrDevolve429() throws Exception {
        doThrow(new RateLimitExceededException(RateLimitExceededException.SCOPE_OCR, 12, RESET, 90))
                .when(rateLimiter).consumir(eq("ocr"), eq(USUARIO_ID.toString()), eq(12), any(Duration.class));

        mockMvc.perform(multipart("/api/collections/upload-batch").file(pdf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(header().string("X-RateLimit-Limit", "12"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("X-RateLimit-Reset", String.valueOf(RESET.getEpochSecond())))
                .andExpect(header().string("X-Ocr-Quota-Limit", "12"))
                .andExpect(header().string("X-Ocr-Quota-Remaining", "0"))
                .andExpect(header().string("X-Ocr-Quota-Reset", RESET_ISO))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.path").value("/api/collections/upload-batch"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OCR")));

        verify(quotaService, never()).consumir();
        verifyNoInteractions(ocrServiceFactory);
    }

    @Test
    @DisplayName("cota diária esgotada devolve 429 com X-Ocr-Quota-*")
    void cotaEsgotadaDevolve429() throws Exception {
        when(quotaService.consumir()).thenThrow(new OcrQuotaExceededException(50, RESET, 120));

        mockMvc.perform(multipart("/api/collections/upload-batch").file(pdf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(header().string("X-RateLimit-Limit", "50"))
                .andExpect(header().string("X-Ocr-Quota-Limit", "50"))
                .andExpect(header().string("X-Ocr-Quota-Remaining", "0"))
                .andExpect(header().string("X-Ocr-Quota-Reset", RESET_ISO))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cota diária de OCR")));

        verifyNoInteractions(ocrServiceFactory);
    }

    @Test
    @DisplayName("sucesso do upload devolve a cota restante nos headers")
    void sucessoDevolveHeadersDeCota() throws Exception {
        when(quotaService.consumir()).thenReturn(new OcrQuotaConsumption(1, 50, RESET));
        when(ocrServiceFactory.getOcrService(any())).thenReturn(ocrService);
        when(ocrService.extractBatchColetas(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(multipart("/api/collections/upload-batch").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Ocr-Quota-Limit", "50"))
                .andExpect(header().string("X-Ocr-Quota-Remaining", "49"))
                .andExpect(header().string("X-Ocr-Quota-Reset", RESET_ISO));

        verify(ocrServiceFactory).getOcrService(isNull());
        verify(ocrService).extractBatchColetas(any(), any(), any());
        verify(quotaService, never()).devolver();
    }

    @Test
    @DisplayName("falha do OCR devolve a cota e mantém o status do provedor")
    void falhaDoOcrDevolveCota() throws Exception {
        when(quotaService.consumir()).thenReturn(new OcrQuotaConsumption(1, 50, RESET));
        when(ocrServiceFactory.getOcrService(any())).thenReturn(ocrService);
        when(ocrService.extractBatchColetas(any(), any(), any()))
                .thenThrow(OcrProviderException.rateLimited(60, null));

        mockMvc.perform(multipart("/api/collections/upload-batch").file(pdf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().doesNotExist("X-Ocr-Quota-Limit"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("provedor de OCR")));

        verify(quotaService).devolver();
    }

    @Test
    @DisplayName("falha ao devolver a cota não mascara o erro original do OCR")
    void falhaAoDevolverNaoMascaraErroOriginal() throws Exception {
        when(quotaService.consumir()).thenReturn(new OcrQuotaConsumption(1, 50, RESET));
        when(ocrServiceFactory.getOcrService(any())).thenReturn(ocrService);
        when(ocrService.extractBatchColetas(any(), any(), any()))
                .thenThrow(OcrProviderException.unavailable(503, null));
        doThrow(new IllegalStateException("banco indisponível")).when(quotaService).devolver();

        mockMvc.perform(multipart("/api/collections/upload-batch").file(pdf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("provedor de OCR")));

        verify(quotaService).devolver();
    }

    @Test
    @DisplayName("rate limit do lote devolve 429 sem headers de cota de OCR")
    void rateLimitDoLoteDevolve429SemHeadersDeOcr() throws Exception {
        doThrow(new RateLimitExceededException(RateLimitExceededException.SCOPE_LOTE, 120, RESET, 30))
                .when(rateLimiter).consumir(eq("lote"), eq(USUARIO_ID.toString()), eq(120), any(Duration.class));

        mockMvc.perform(post("/api/collections/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().doesNotExist("X-Ocr-Quota-Limit"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lote")));

        verifyNoInteractions(coletaService);
    }
}
