package com.erp.ocr.service.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.erp.ocr.dto.CollectionExtractedDto;
import com.erp.ocr.exception.OcrProviderException;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.ExpectedCount.once;

/**
 * Cobertura de {@link GeminiOcrService} no caminho Vertex AI. Nenhuma chamada
 * de rede real: o {@link RestTemplate} é ligado a um
 * {@link MockRestServiceServer} e o token vem de um provedor fake — ADC nunca
 * é lido nos testes.
 */
class GeminiOcrServiceTest {

    private static final String PROJETO = "projeto-teste";
    private static final String LOCALIZACAO = "global";
    private static final String TOKEN_FAKE = "token-de-teste-fake";
    private static final String MODELO = "gemini-3.6-flash";
    private static final String FALLBACK = "gemini-3.5-flash";

    private static final String URL_MODELO = urlDoModelo(MODELO);
    private static final String URL_FALLBACK = urlDoModelo(FALLBACK);

    private static final byte[] IMAGEM_FAKE = "conteudo-binario-da-folha-de-coleta".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SupplierRepository produtorRepository;

    @Mock
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    private GeminiOcrService geminiOcrService;
    private MockRestServiceServer servidor;
    private AtomicReference<JsonNode> corpoDaRequisicao;

    private static String urlDoModelo(String modelo) {
        return "https://aiplatform.googleapis.com/v1/projects/" + PROJETO
                + "/locations/" + LOCALIZACAO + "/publishers/google/models/" + modelo + ":generateContent";
    }

    /**
     * Supplier de teste montado por setters. O Supplier passou a exigir
     * organizacao (coluna NOT NULL) e nao tem mais construtor de todos os
     * argumentos; montar por posicao seria fragil e oTenant teria de entrar
     * na posicao certa sem o compilador ajudar.
     */
    private static Supplier produtorJoao() {
        Supplier p = new Supplier();
        p.setId(11L);
        p.setNome("João Silva");
        p.setLinha("Linha 1");
        p.setChavePix("pix");
        p.setFormaPagamento("Dinheiro");
        p.setAtivo(true);
        p.setUsaPagamentoPorQualidade(false);
        Organization org = new Organization("Cooperativa Teste");
        org.setId(1L);
        p.setOrganization(org);
        return p;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(produtorRepository.findVisiveisPorNome(anyString(), any()))
                .thenAnswer(invoc -> {
                    String nome = semAcento(invoc.<String>getArgument(0));
                    // findVisiveisPorNome devolve lista (o servico pega o
                    // primeiro), e o segundo argumento e a organizacao do
                    // usuario autenticado — any() para nao acoplar o teste ao
                    // id, que e o mesmo em todos os cenarios.
                    return nome.contains("joao silva")
                            ? List.of(produtorJoao())
                            : List.of();
                });

        // O servico filtra o produtor pela organizacao do usuario
        // autenticado. Sem mock, organizacaoId() devolveria null em mock
        // Mockito (retorno padrao) e o filtro viraria "sem escopo".
        when(organizacaoContexto.organizacaoId()).thenReturn(1L);

        geminiOcrService = servico(20, 1);
        servidor = MockRestServiceServer.bindTo(restTemplateInternaDe(geminiOcrService)).build();
        corpoDaRequisicao = new AtomicReference<>();
    }

    private GeminiOcrService servico(int maxPdfPages, int maxAttempts) {
        return new GeminiOcrService(PROJETO, LOCALIZACAO, MODELO, List.of(FALLBACK), 1000, 1000,
                maxPdfPages, maxAttempts, 0L, 1.0d, produtorRepository, organizacaoContexto,
                () -> TOKEN_FAKE);
    }

    private static RestTemplate restTemplateInternaDe(GeminiOcrService servico) {
        return (RestTemplate) ReflectionTestUtils.getField(servico, "restTemplate");
    }

    private MockMultipartFile imagem(String nome, String contentType, byte[] bytes) {
        return new MockMultipartFile("arquivo", nome, contentType, bytes);
    }

    /** Envelopa um texto de resposta do Gemini no JSON da API. */
    private static String respostaGemini(String texto) {
        try {
            return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                    + new ObjectMapper().writeValueAsString(texto) + "}]}}]}";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void esperarUmaChamadaComResposta(String corpoJson) {
        esperarChamadaComResposta(URL_MODELO, corpoJson);
    }

    private void esperarChamadaComResposta(String url, String corpoJson) {
        servidor.expect(once(), requestTo(url))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN_FAKE))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().string(new CapturaCorpo(corpoDaRequisicao)))
                .andRespond(withSuccess(corpoJson, MediaType.APPLICATION_JSON));
    }

    /**
     * Matcher que guarda o corpo enviado para a API, permitindo verificar o
     * prompt e a imagem em base64 depois da chamada.
     */
    private static final class CapturaCorpo extends BaseMatcher<String> {

        private final AtomicReference<JsonNode> destino;

        private CapturaCorpo(AtomicReference<JsonNode> destino) {
            this.destino = destino;
        }

        @Override
        public boolean matches(Object item) {
            destino.set(lerJson(item.toString()));
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("corpo JSON enviado ao Gemini");
        }
    }

    private static JsonNode lerJson(String texto) {
        try {
            return new ObjectMapper().readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    // ------------------------------------------------------------------
    // Extração de imagem
    // ------------------------------------------------------------------

    @Test
    void extractBatchColetas_DeveMapearLinhasParaSupplierCadastrado() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("""
                [{"produtorNome":"João Silva","dia":1,"quantidadeLitros":150},
                 {"produtorNome":"João Silva","dia":2,"quantidadeLitros":142}]
                """));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertEquals(2, coletas.size());
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 1), 150),
                coletas.get(0));
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 2), 142),
                coletas.get(1));
    }

    @Test
    void extractBatchColetas_DeveUsarOAnoMesInformadoEAoVezDoMesCorrente() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini(
                "[{\"produtorNome\":\"João Silva\",\"dia\":28,\"quantidadeLitros\":9}]"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.png", "image/png", IMAGEM_FAKE), 2019, 2);

        // B17: a folha é de 02/2019 mesmo sendo importada hoje.
        assertEquals(LocalDate.of(2019, 2, 28), coletas.get(0).data());
    }

    @Test
    void extractBatchColetas_DeveEnviarPromptEImagemBase64NoCorpoDaRequisicao() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("[]"));

        geminiOcrService.extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        JsonNode conteudo = corpoDaRequisicao.get().at("/contents/0/parts");
        assertEquals(2, conteudo.size());
        String prompt = conteudo.get(0).get("text").asText();
        assertTrue(prompt.contains("quantidadeLitros"), "o prompt deveria pedir o JSON esperado");
        assertEquals("image/jpeg", conteudo.get(1).at("/inlineData/mimeType").asText());
        assertEquals(Base64.getEncoder().encodeToString(IMAGEM_FAKE),
                conteudo.get(1).at("/inlineData/data").asText());
        assertEquals("application/json",
                corpoDaRequisicao.get().at("/generationConfig/responseMimeType").asText());
        assertEquals("user", corpoDaRequisicao.get().at("/contents/0/role").asText(),
                "o Vertex AI exige role explicito no content");
    }

    @Test
    void extractBatchColetas_DeveAssumirImageJpegQuandoOArquivoNaoDeclaraContentType() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("[]"));

        geminiOcrService.extractBatchColetas(imagem("folha", null, IMAGEM_FAKE), 2025, 3);

        assertEquals("image/jpeg", corpoDaRequisicao.get().at("/contents/0/parts/1/inlineData/mimeType").asText());
    }

    @Test
    void extractBatchColetas_DeveDevolverLinhaSemSupplierComoNaoConciliada() throws Exception {
        // B17: antes a linha era só logada e sumia; agora volta para conferência.
        esperarUmaChamadaComResposta(respostaGemini(
                "[{\"produtorNome\":\"Maria Souza\",\"dia\":5,\"quantidadeLitros\":80}]"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertEquals(1, coletas.size());
        CollectionExtractedDto coleta = coletas.get(0);
        assertFalse(coleta.matched());
        assertNull(coleta.produtorId());
        assertEquals("Maria Souza", coleta.produtorNomeDetectado());
        assertEquals(80, coleta.quantidadeLitros());
    }

    @Test
    void extractBatchColetas_DeveAceitarRespostaDelimitadaPorBlocoDeCodigo() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("""
                ```json
                [{"produtorNome":"João Silva","dia":3,"quantidadeLitros":100}]
                ```
                """));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertEquals(1, coletas.size());
        assertEquals(100, coletas.get(0).quantidadeLitros());
    }

    @Test
    void extractBatchColetas_DeveDesembrulharRespostaComChaveData() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini(
                "{\"data\":[{\"produtorNome\":\"João Silva\",\"dia\":4,\"quantidadeLitros\":77}]}"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertEquals(1, coletas.size());
        assertEquals(LocalDate.of(2025, 3, 4), coletas.get(0).data());
    }

    @Test
    void extractBatchColetas_DeveDescartarJsonQueNaoEhArrayNemTemData() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("{\"resposta\":\"não consegui ler a imagem\"}"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertNotNull(coletas);
        assertTrue(coletas.isEmpty());
    }

    @Test
    void extractBatchColetas_DeveIgnorarVolumeZeroDiaForaDoRangeEDataInexistente() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("""
                [{"produtorNome":"João Silva","dia":1,"quantidadeLitros":0},
                 {"produtorNome":"João Silva","dia":32,"quantidadeLitros":10},
                 {"produtorNome":"João Silva","dia":0,"quantidadeLitros":10},
                 {"produtorNome":"João Silva","dia":30,"quantidadeLitros":-5},
                 {"produtorNome":"João Silva","dia":31,"quantidadeLitros":55},
                 {"produtorNome":"João Silva","dia":6,"quantidadeLitros":64}]
                """));

        // abril/2025 tem 30 dias: a linha de dia 31 não existe e some.
        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 4);

        assertEquals(1, coletas.size());
        assertEquals(LocalDate.of(2025, 4, 6), coletas.get(0).data());
        assertEquals(64, coletas.get(0).quantidadeLitros());
    }

    @Test
    void extractBatchColetas_DeveAssumirMesPassadoQuandoAnoEMesNaoInformados() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini(
                "[{\"produtorNome\":\"João Silva\",\"dia\":1,\"quantidadeLitros\":50}]"));
        LocalDate mesPassado = LocalDate.now().minusMonths(1);

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), null, null);

        assertEquals(1, coletas.size());
        assertEquals(mesPassado.withDayOfMonth(1), coletas.get(0).data());
    }

    @Test
    void extractBatchColetas_DeveRejeitarMesForaDoIntervaloUmADoze() {
        MockMultipartFile arquivo = imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE);

        assertThrows(IllegalArgumentException.class,
                () -> geminiOcrService.extractBatchColetas(arquivo, 2025, 13));
        assertThrows(IllegalArgumentException.class,
                () -> geminiOcrService.extractBatchColetas(arquivo, 2025, 0));
    }

    @Test
    void extractBatchColetas_DeveClassificarErroDoProvedorSemVirar500() {
        GeminiOcrService semFallback = new GeminiOcrService(PROJETO, LOCALIZACAO, MODELO, List.of(), 1000,
                1000, 20, 1, 0L, 1.0d, produtorRepository, organizacaoContexto, () -> TOKEN_FAKE);
        servidor = MockRestServiceServer.bindTo(restTemplateInternaDe(semFallback)).build();
        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "42"));

        OcrProviderException erro = assertThrows(OcrProviderException.class,
                () -> semFallback.extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, erro.getStatus());
        assertEquals(42L, erro.getRetryAfterSeconds());
    }

    @Test
    void extractBatchColetas_DeveFalharQuandoARespostaNaoTemTextoExtraivel() {
        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        RuntimeException erro = assertThrows(RuntimeException.class,
                () -> geminiOcrService.extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3));

        assertTrue(erro.getMessage().contains("Could not extract text from Gemini response"), erro.getMessage());
    }

    @Test
    void extractBatchColetas_DeveUsarFallbackQuandoOModeloPrincipalEstiverIndisponivel() throws Exception {
        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        esperarChamadaComResposta(URL_FALLBACK, respostaGemini(
                "[{\"produtorNome\":\"João Silva\",\"dia\":1,\"quantidadeLitros\":10}]"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertEquals(1, coletas.size());
        servidor.verify();
    }

    @Test
    void extractBatchColetas_DeveRepetirFalhaTransitoriaAntesDoFallback() throws Exception {
        GeminiOcrService comRetry = servico(20, 3);
        servidor = MockRestServiceServer.bindTo(restTemplateInternaDe(comRetry)).build();

        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        esperarChamadaComResposta(URL_MODELO, respostaGemini("[]"));

        List<CollectionExtractedDto> coletas = comRetry
                .extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3);

        assertTrue(coletas.isEmpty());
        servidor.verify();
    }

    @Test
    void extractBatchColetas_NaoDeveTentarFallbackQuandoFaltaCreditoOuPermissao() {
        servidor.expect(once(), requestTo(URL_MODELO))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        OcrProviderException erro = assertThrows(OcrProviderException.class,
                () -> geminiOcrService.extractBatchColetas(imagem("folha.jpg", "image/jpeg", IMAGEM_FAKE), 2025, 3));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erro.getStatus());
        assertEquals(402, erro.getUpstreamStatus());
        assertFalse(erro.isFallbackEligible());
        servidor.verify();
    }

    // ------------------------------------------------------------------
    // Extração de PDF (convertido página a página)
    // ------------------------------------------------------------------

    @Test
    void extractBatchColetas_DeveConverterPaginasDePdfEmImagens() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini(
                "[{\"produtorNome\":\"João Silva\",\"dia\":7,\"quantidadeLitros\":210}]"));

        List<CollectionExtractedDto> coletas = geminiOcrService
                .extractBatchColetas(imagem("planilha.pdf", "application/pdf", pdfCom(1)), 2025, 3);

        assertEquals(1, coletas.size());
        assertEquals(LocalDate.of(2025, 3, 7), coletas.get(0).data());
        // A página virou JPEG antes de ir para a API (processamento mais rápido).
        assertEquals("image/jpeg", corpoDaRequisicao.get().at("/contents/0/parts/1/inlineData/mimeType").asText());
    }

    @Test
    void extractBatchColetas_DeveTratarPdfPeloNomeDoArquivoMesmoSemContentType() throws Exception {
        esperarUmaChamadaComResposta(respostaGemini("[]"));

        geminiOcrService.extractBatchColetas(imagem("planilha.pdf", null, pdfCom(1)), 2025, 3);

        servidor.verify();
    }

    @Test
    void extractBatchColetas_DeveProcessarNoMaximoAsPaginasConfiguradas() throws Exception {
        GeminiOcrService comTetoDeUmaPagina = servico(1, 1);
        servidor = MockRestServiceServer.bindTo(restTemplateInternaDe(comTetoDeUmaPagina)).build();
        esperarUmaChamadaComResposta(respostaGemini(
                "[{\"produtorNome\":\"João Silva\",\"dia\":1,\"quantidadeLitros\":10}]"));

        List<CollectionExtractedDto> coletas = comTetoDeUmaPagina.extractBatchColetas(
                imagem("planilha.pdf", "application/pdf", pdfCom(3)), 2025, 3);

        // 3 páginas, teto 1: só a primeira vira requisição, e o servidor rejeitaria
        // uma segunda chamada.
        assertEquals(1, coletas.size());
        servidor.verify();
    }

    private static byte[] pdfCom(int paginas) throws Exception {
        try (PDDocument documento = new PDDocument()) {
            for (int i = 0; i < paginas; i++) {
                documento.addPage(new PDPage(PDRectangle.A5));
            }
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // Configuração
    // ------------------------------------------------------------------

    @Test
    void isConfigured_DeveReconhecerProjetoELocalizacaoComTokenProvider() {
        assertTrue(geminiOcrService.isConfigured());
    }

    @Test
    void isConfigured_DeveRetornarFalseSemProjetoOuSemCredenciais() {
        VertexAccessTokenProvider semCredenciais = new VertexAccessTokenProvider() {
            @Override
            public String accessToken() {
                return TOKEN_FAKE;
            }

            @Override
            public boolean isConfigured() {
                return false;
            }
        };

        GeminiOcrService semProjeto = new GeminiOcrService("", LOCALIZACAO, MODELO, List.of(FALLBACK), 1000,
                1000, 1, 1, 0L, 1.0d, produtorRepository, organizacaoContexto, () -> TOKEN_FAKE);
        GeminiOcrService semAdc = new GeminiOcrService(PROJETO, LOCALIZACAO, MODELO, List.of(FALLBACK), 1000,
                1000, 1, 1, 0L, 1.0d, produtorRepository, organizacaoContexto, semCredenciais);

        assertFalse(semProjeto.isConfigured());
        assertFalse(semAdc.isConfigured());
    }

    @Test
    void getServiceName_DeveConterOModeloConfigurado() {
        assertEquals("Google Gemini Vertex AI (" + MODELO + ")", geminiOcrService.getServiceName());
    }

    @Test
    void construtor_DeveAceitarNoMinimoUmaPaginaDePdf() {
        GeminiOcrService comTetoZero = servico(0, 1);

        // maxPdfPages=0 viraria laço infinito em processPdfAsImages se não fosse
        // normalizado para >= 1.
        assertNotNull(comTetoZero);
        assertEquals(1, ((Integer) ReflectionTestUtils.getField(comTetoZero, "maxPdfPages")).intValue());
    }
}
