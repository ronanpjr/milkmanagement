package com.erp.ocr.service.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.ResponseEntity;
import com.erp.ocr.config.GlobalExceptionHandler;
import com.erp.ocr.dto.ApiErrorDto;
import com.erp.ocr.repository.SupplierRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Cobertura de {@link OcrServiceFactory} — a escolha do provedor de OCR é feita
 * por parâmetro de request, então um nome desconhecido não pode devolver um
 * serviço inesperado nem estourar 500. Um provedor sem credenciais (projeto
 * ausente ou o placeholder literal {@code undefined}) deve falhar cedo, com
 * 400, antes de tentar processar o arquivo.
 */
class OcrServiceFactoryTest {

    private static final String ENDPOINT_AZURE_OK = "https://endpoint-de-teste.invalid";
    private static final String CHAVE_OK = "chave-de-teste-fake-sem-valor-real";
    private static final String MODELO = "gemini-3.6-flash";
    private static final String PROJETO = "projeto-de-teste";
    private static final String TOKEN_FAKE = "token-de-teste-fake";

    // Os dois servicos de OCR passaram a receber o OrganizationContext: o
    // produtor resolvido por nome e filtrado pela organizacao do usuario
    // autenticado. Aqui so interessa a escolha de provedor, entao o contexto
    // pode ser um mock sem stub — nenhuma consulta chega a ser feita.
    private final com.erp.ocr.config.OrganizationContext organizacaoContexto =
            mock(com.erp.ocr.config.OrganizationContext.class);

    private final GeminiOcrService gemini = gemini(PROJETO);
    private final com.erp.ocr.service.AzureOcrService azure =
            new com.erp.ocr.service.AzureOcrService(ENDPOINT_AZURE_OK, CHAVE_OK,
                    mock(SupplierRepository.class), organizacaoContexto);

    private GeminiOcrService gemini(String projeto) {
        return new GeminiOcrService(projeto, "global", MODELO, List.of(), 1000, 1000, 20, 1, 0L,
                1.0d, mock(SupplierRepository.class), organizacaoContexto, () -> TOKEN_FAKE);
    }

    private OcrServiceFactory factoryComPadrao(String padrao) {
        OcrServiceFactory factory = new OcrServiceFactory(gemini, azure);
        ReflectionTestUtils.setField(factory, "defaultService", padrao);
        ReflectionTestUtils.setField(factory, "azureEndpoint", ENDPOINT_AZURE_OK);
        ReflectionTestUtils.setField(factory, "azureKey", CHAVE_OK);
        return factory;
    }

    @Test
    void getOcrService_DeveEscolherGeminiQuandoPedido() {
        assertSame(gemini, factoryComPadrao("gemini").getOcrService("gemini"));
    }

    @Test
    void getOcrService_DeveEscolherAzureQuandoPedido() {
        assertSame(azure, factoryComPadrao("gemini").getOcrService("azure"));
    }

    @Test
    void getOcrService_DeveIgnorarMaiusculasMinusculasDoParametro() {
        OcrServiceFactory factory = factoryComPadrao("gemini");

        assertSame(azure, factory.getOcrService("AZURE"));
        assertSame(gemini, factory.getOcrService("Gemini"));
    }

    @Test
    void getOcrService_DeveUsarOPadraoQuandoNaoHaParametro() {
        assertSame(gemini, factoryComPadrao("gemini").getOcrService(null));
        assertSame(azure, factoryComPadrao("azure").getOcrService(null));
    }

    @Test
    void getOcrService_DeveCairNoPadraoQuandoOProvedorNaoExiste() {
        assertSame(azure, factoryComPadrao("azure").getOcrService("tesseract"));
        assertSame(gemini, factoryComPadrao("gemini").getOcrService("tesseract"));
    }

    @Test
    void getOcrService_DeveCairNoGeminiQuandoNemOPedidoNemOPadraoSaoConhecidos() {
        assertSame(gemini, factoryComPadrao("indefinido").getOcrService("indefinido"));
    }

    @Test
    void getDefaultOcrService_DeveRetornarOServicoPadraoConfigurado() {
        assertSame(gemini, factoryComPadrao("gemini").getDefaultOcrService());
        assertSame(azure, factoryComPadrao("azure").getDefaultOcrService());
    }

    // ------------------------------------------------------------------
    // Provedor não configurado: falha clara (400), não 500
    // ------------------------------------------------------------------

    @Test
    void getOcrService_DeveRecusarGeminiSemProjeto() {
        GeminiOcrService semChave = gemini("undefined");
        OcrServiceFactory factory = new OcrServiceFactory(semChave, azure);
        ReflectionTestUtils.setField(factory, "defaultService", "gemini");
        ReflectionTestUtils.setField(factory, "azureEndpoint", ENDPOINT_AZURE_OK);
        ReflectionTestUtils.setField(factory, "azureKey", CHAVE_OK);

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> factory.getOcrService("gemini"));
        assertTrue(erro.getMessage().contains("OCR não configurado"), erro.getMessage());
    }

    @Test
    void getOcrService_DeveRecusarAzureComEndpointUndefined() {
        OcrServiceFactory factory = new OcrServiceFactory(gemini, azure);
        ReflectionTestUtils.setField(factory, "defaultService", "azure");
        ReflectionTestUtils.setField(factory, "azureEndpoint", "https://undefined.cognitiveservices.azure.com/");
        ReflectionTestUtils.setField(factory, "azureKey", CHAVE_OK);

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> factory.getOcrService("azure"));
        assertTrue(erro.getMessage().contains("OCR não configurado"), erro.getMessage());
    }

    @Test
    void getOcrService_DeveRecusarAzureComChaveUndefined() {
        OcrServiceFactory factory = new OcrServiceFactory(gemini, azure);
        ReflectionTestUtils.setField(factory, "defaultService", "azure");
        ReflectionTestUtils.setField(factory, "azureEndpoint", ENDPOINT_AZURE_OK);
        ReflectionTestUtils.setField(factory, "azureKey", "undefined");

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> factory.getOcrService("azure"));
        assertTrue(erro.getMessage().contains("OCR não configurado"), erro.getMessage());
    }

    @Test
    void getOcrService_DeveRecusarOPadraoNaoConfiguradoQuandoNaoHaParametro() {
        GeminiOcrService semChave = gemini("");
        OcrServiceFactory factory = new OcrServiceFactory(semChave, azure);
        ReflectionTestUtils.setField(factory, "defaultService", "gemini");
        ReflectionTestUtils.setField(factory, "azureEndpoint", ENDPOINT_AZURE_OK);
        ReflectionTestUtils.setField(factory, "azureKey", CHAVE_OK);

        assertThrows(IllegalStateException.class, factory::getDefaultOcrService);
    }

    /**
     * A exceção lançada pela factory precisa virar 400 (erro do cliente / má
     * configuração exposta), nunca 500. O {@link GlobalExceptionHandler} mapeia
     * {@link IllegalStateException} para 400 — este teste garante que a escolha
     * do tipo de exceção mantém essa promessa.
     */
    @Test
    void provedorNaoConfigurado_DeveVirar400ENao500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/collections/upload-batch");
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        GeminiOcrService semChave = gemini("undefined");
        OcrServiceFactory semGemini = new OcrServiceFactory(semChave, azure);
        ReflectionTestUtils.setField(semGemini, "defaultService", "gemini");
        ReflectionTestUtils.setField(semGemini, "azureEndpoint", ENDPOINT_AZURE_OK);
        ReflectionTestUtils.setField(semGemini, "azureKey", CHAVE_OK);

        OcrServiceFactory semAzure = new OcrServiceFactory(gemini, azure);
        ReflectionTestUtils.setField(semAzure, "defaultService", "azure");
        ReflectionTestUtils.setField(semAzure, "azureEndpoint", "https://undefined.cognitiveservices.azure.com/");
        ReflectionTestUtils.setField(semAzure, "azureKey", "undefined");

        IllegalStateException erroGemini = assertThrows(IllegalStateException.class,
                () -> semGemini.getOcrService("gemini"));
        ResponseEntity<ApiErrorDto> respostaGemini = handler.handleBadRequest(erroGemini, request);
        assertEquals(400, respostaGemini.getStatusCode().value());

        IllegalStateException erroAzure = assertThrows(IllegalStateException.class,
                () -> semAzure.getOcrService("azure"));
        ResponseEntity<ApiErrorDto> respostaAzure = handler.handleBadRequest(erroAzure, request);
        assertEquals(400, respostaAzure.getStatusCode().value());
    }
}
