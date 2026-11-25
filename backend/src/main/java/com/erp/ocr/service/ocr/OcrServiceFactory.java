package com.erp.ocr.service.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.erp.ocr.service.AzureOcrService;

/**
 * Factory for selecting OCR service based on runtime parameter or
 * configuration.
 * Supports user-configurable service selection per request.
 */
@Service
public class OcrServiceFactory {

    private static final Logger logger = LoggerFactory.getLogger(OcrServiceFactory.class);

    private final GeminiOcrService geminiService;
    private final AzureOcrService azureService;

    @Value("${ocr.service.default:gemini}")
    private String defaultService;

    // Achado 11: o provedor Azure é lido aqui apenas para recusar a seleção de
    // um OCR sem credenciais. application.properties usa o literal "undefined"
    // como default quando a variável de ambiente não existe; antes, essa chave
    // falsa era aceita e só estourava 500 lá dentro do SDK. Agora a falha é
    // clara, na borda, e vira 400 pelo GlobalExceptionHandler.
    @Value("${azure.document-intelligence.endpoint:undefined}")
    private String azureEndpoint;

    @Value("${azure.document-intelligence.key:undefined}")
    private String azureKey;

    @Autowired
    public OcrServiceFactory(GeminiOcrService geminiService, AzureOcrService azureService) {
        this.geminiService = geminiService;
        this.azureService = azureService;
    }

    /**
     * Get OCR service by name.
     * <p>
     * Antes de devolver o serviço, confere que o provedor escolhido tem
     * credenciais válidas. Um provedor não configurado (chave ausente ou o
     * placeholder literal {@code undefined}) resulta em
     * {@link IllegalStateException}, que o {@code GlobalExceptionHandler} mapeia
     * para 400 — e não em 500 no meio do processamento do arquivo.
     *
     * @param serviceName "gemini" or "azure", null for default
     * @return Selected OcrService implementation
     */
    public OcrService getOcrService(String serviceName) {
        String service = serviceName != null ? serviceName : defaultService;

        OcrService selecionado;
        if ("azure".equalsIgnoreCase(service)) {
            selecionado = azureService;
        } else if ("gemini".equalsIgnoreCase(service)) {
            selecionado = geminiService;
        } else {
            logger.warn("Unknown OCR service '{}', defaulting to {}", service, defaultService);
            selecionado = "azure".equalsIgnoreCase(defaultService) ? azureService : geminiService;
        }

        validarConfiguracao(selecionado);
        logger.info("Using OCR service: {}", selecionado.getServiceName());
        return selecionado;
    }

    /**
     * Get default OCR service based on configuration.
     * 
     * @return Default OcrService implementation
     */
    public OcrService getDefaultOcrService() {
        return getOcrService(null);
    }

    private void validarConfiguracao(OcrService selecionado) {
        boolean configurado = selecionado == azureService
                ? estaDefinido(azureEndpoint) && estaDefinido(azureKey)
                : geminiService.isConfigured();
        if (!configurado) {
            throw new IllegalStateException(
                    "OCR não configurado: o provedor selecionado não possui credenciais válidas. "
                            + "Para o Gemini/Vertex AI, defina GEMINI_PROJECT_ID e use Application Default "
                            + "Credentials (service account da VM ou `gcloud auth application-default login`); "
                            + "para o Azure, defina AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT/"
                            + "AZURE_DOCUMENT_INTELLIGENCE_KEY.");
        }
    }

    private static boolean estaDefinido(String valor) {
        return valor != null && !valor.isBlank() && !valor.toLowerCase().contains("undefined");
    }
}
