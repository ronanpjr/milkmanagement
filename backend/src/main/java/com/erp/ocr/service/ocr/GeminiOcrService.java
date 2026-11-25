package com.erp.ocr.service.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.erp.ocr.dto.CollectionExtractedDto;
import com.erp.ocr.exception.OcrProviderException;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * OCR Service implementation using Google Gemini via Vertex AI.
 * <p>
 * A autenticação é por Application Default Credentials (ADC), não por API key.
 * Em produção o token vem da service account da VM (metadata server); o
 * Workload Identity Federation do deploy já segue o mesmo princípio — nenhuma
 * chave JSON fica na imagem ou no repositório.
 * <p>
 * O endpoint do Vertex AI é o que permite usar os créditos normais do Google
 * Cloud. O Gemini API por API key (AI Studio) usa uma carteira Prepay separada,
 * que é a origem do HTTP 402 "prepayment credits are depleted" que derrubou o
 * OCR em produção.
 */
@Service
public class GeminiOcrService implements OcrService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiOcrService.class);

    private static final String GLOBAL_LOCATION = "global";
    private static final String GLOBAL_HOST = "aiplatform.googleapis.com";
    private static final String DEFAULT_MODEL = "gemini-3.6-flash";
    private static final String DEFAULT_FALLBACK_MODELS = "gemini-3.5-flash,gemini-3.7-flash";

    private final String projectId;
    private final String location;
    private final String modelName;
    private final List<String> fallbackModels;
    private final SupplierRepository produtorRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final int maxPdfPages;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final double retryMultiplier;
    private final VertexAccessTokenProvider tokenProvider;
    private final com.erp.ocr.config.OrganizationContext organizacaoContexto;

    @Autowired
    public GeminiOcrService(
            @Value("${gemini.vertex.project-id:}") String projectId,
            @Value("${gemini.vertex.location:global}") String location,
            @Value("${gemini.vertex.model:gemini-3.6-flash}") String modelName,
            @Value("${gemini.vertex.fallback-models:gemini-3.5-flash,gemini-3.7-flash}") String fallbackModels,
            @Value("${gemini.vertex.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${gemini.vertex.read-timeout-ms:60000}") int readTimeoutMs,
            @Value("${gemini.vertex.max-pdf-pages:20}") int maxPdfPages,
            @Value("${gemini.vertex.retry.max-attempts:3}") int maxAttempts,
            @Value("${gemini.vertex.retry.initial-delay-ms:500}") long retryDelayMs,
            @Value("${gemini.vertex.retry.multiplier:2.0}") double retryMultiplier,
            SupplierRepository produtorRepository,
            com.erp.ocr.config.OrganizationContext organizacaoContexto) {
        this(projectId, location, modelName, parseModels(fallbackModels), connectTimeoutMs, readTimeoutMs,
                maxPdfPages, maxAttempts, retryDelayMs, retryMultiplier, produtorRepository,
                organizacaoContexto, new GoogleVertexAccessTokenProvider());
    }

    /**
     * Construtor usado pelos testes: a única diferença é o provedor de token,
     * que evita qualquer chamada ao metadata server/ADC.
     */
    GeminiOcrService(String projectId, String location, String modelName, List<String> fallbackModels,
            int connectTimeoutMs, int readTimeoutMs, int maxPdfPages, int maxAttempts,
            long retryDelayMs, double retryMultiplier, SupplierRepository produtorRepository,
            com.erp.ocr.config.OrganizationContext organizacaoContexto,
            VertexAccessTokenProvider tokenProvider) {
        this.projectId = projectId == null ? "" : projectId.trim();
        this.location = isBlank(location) ? GLOBAL_LOCATION : location.trim();
        this.modelName = isBlank(modelName) ? DEFAULT_MODEL : modelName.trim();
        this.fallbackModels = List.copyOf(fallbackModels == null ? List.of() : fallbackModels);
        this.produtorRepository = produtorRepository;
        this.organizacaoContexto = organizacaoContexto;
        this.objectMapper = new ObjectMapper();
        this.maxPdfPages = Math.max(1, maxPdfPages);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
        this.retryMultiplier = Math.max(1.0d, retryMultiplier);
        this.tokenProvider = tokenProvider;

        // B16: RestTemplate() with no timeouts can hang indefinitely on a stalled
        // connection. Fail fast instead.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public List<CollectionExtractedDto> extractBatchColetas(MultipartFile file, Integer ano, Integer mes)
            throws IOException {
        logger.info("Starting Gemini OCR extraction for file: {}", file.getOriginalFilename());

        int[] targetYearMonth = resolveTargetYearMonth(ano, mes);
        int targetYear = targetYearMonth[0];
        int targetMonth = targetYearMonth[1];

        try {
            byte[] fileBytes = file.getBytes();
            String mimeType = file.getContentType() != null ? file.getContentType() : "image/jpeg";

            // Check if file is PDF - convert to images for faster processing
            if (isPdf(mimeType, file.getOriginalFilename())) {
                logger.info("PDF detected, converting pages to JPG for faster processing");
                return processPdfAsImages(fileBytes, targetYear, targetMonth);
            }

            // Process as regular image
            return processImage(fileBytes, mimeType, targetYear, targetMonth);

        } catch (OcrProviderException e) {
            // Já é uma falha classificada do provedor; o GlobalExceptionHandler
            // converte no status certo (429/503) em vez de 500 genérico.
            throw e;
        } catch (Exception e) {
            logger.error("Error during Gemini OCR extraction", e);
            throw new RuntimeException("Failed to process document with Gemini: " + e.getMessage(), e);
        }
    }

    /**
     * B17: the sheet's year/month used to come from an unconditional
     * LocalDate.now().minusMonths(1) assumption, which produced wrong dates when
     * importing an old sheet. Now it's an explicit request param, defaulting to
     * "last month" only when the caller doesn't provide one (keeps prior
     * behavior for existing callers).
     */
    private int[] resolveTargetYearMonth(Integer ano, Integer mes) {
        if (ano != null && mes != null) {
            if (mes < 1 || mes > 12) {
                throw new IllegalArgumentException("O mês deve estar entre 1 e 12.");
            }
            return new int[] { ano, mes };
        }
        LocalDate lastMonthDate = LocalDate.now().minusMonths(1);
        return new int[] { lastMonthDate.getYear(), lastMonthDate.getMonthValue() };
    }

    /**
     * Check if file is a PDF
     */
    private boolean isPdf(String mimeType, String filename) {
        return "application/pdf".equals(mimeType) ||
                (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    /**
     * Convert PDF pages to images and process each page
     */
    private List<CollectionExtractedDto> processPdfAsImages(byte[] pdfBytes, int year, int month) throws IOException {
        List<CollectionExtractedDto> allColetas = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            // Cada página vira uma BufferedImage em heap E uma chamada paga à
            // API de OCR. Sem este teto, um PDF de 10MB com milhares de páginas
            // esgotaria a memória e multiplicaria o custo por request.
            int pagesToProcess = Math.min(pageCount, maxPdfPages);
            if (pageCount > maxPdfPages) {
                logger.warn("PDF has {} pages; processing only the first {} (gemini.vertex.max-pdf-pages).",
                        pageCount, maxPdfPages);
            }
            logger.info("PDF has {} pages, converting {} to JPG", pageCount, pagesToProcess);

            for (int pageIndex = 0; pageIndex < pagesToProcess; pageIndex++) {
                logger.info("Processing page {} of {}", pageIndex + 1, pagesToProcess);

                // Render page as image (150 DPI for good quality/speed balance)
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 150);

                // Convert BufferedImage to JPG bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", baos);
                byte[] imageBytes = baos.toByteArray();

                logger.info("Page {} converted to JPG: {} KB", pageIndex + 1, imageBytes.length / 1024);

                // Process the image
                List<CollectionExtractedDto> pageColetas = processImage(imageBytes, "image/jpeg", year, month);
                allColetas.addAll(pageColetas);

                logger.info("Page {} extracted {} coletas", pageIndex + 1, pageColetas.size());
            }
        }

        logger.info("Total coletas from PDF: {}", allColetas.size());
        return allColetas;
    }

    /**
     * Process a single image (original or converted from PDF page)
     */
    private List<CollectionExtractedDto> processImage(byte[] imageBytes, String mimeType, int year, int month)
            throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Build request body
        String requestBody = buildRequestBody(base64Image, mimeType);

        // Call Vertex AI
        String responseBody = callVertex(requestBody);
        String responseText = extractTextFromResponse(responseBody);
        logger.info("Gemini response received: {} characters", responseText.length());
        logger.debug("Gemini raw response: {}", responseText);

        // Parse JSON response
        List<CollectionExtractedDto> coletas = parseGeminiResponse(responseText, year, month);
        logger.info("Extracted {} coletas from Gemini response", coletas.size());

        return coletas;
    }

    /**
     * Tenta o modelo principal e, se ele estiver indisponível/limitado, os
     * modelos de fallback. Cada modelo tem seus próprios retries para falhas
     * transitórias (429/5xx/rede).
     */
    private String callVertex(String requestBody) throws IOException {
        OcrProviderException ultimaFalha = null;

        for (String model : modelosParaTentar()) {
            try {
                return chamarModeloComRetry(model, requestBody);
            } catch (OcrProviderException e) {
                if (!e.isFallbackEligible()) {
                    throw e;
                }
                ultimaFalha = e;
                logger.warn("Modelo Gemini {} falhou (upstream={}, status={}); tentando o próximo.",
                        model, e.getUpstreamStatus(), e.getStatus().value());
            }
        }

        if (ultimaFalha != null) {
            throw ultimaFalha;
        }
        throw OcrProviderException.modelUnavailable(modelName, null);
    }

    private String chamarModeloComRetry(String model, String requestBody) throws IOException {
        long espera = retryDelayMs;
        OcrProviderException ultimaFalha = null;

        for (int tentativa = 1; tentativa <= maxAttempts; tentativa++) {
            try {
                return chamarModelo(model, requestBody);
            } catch (OcrProviderException e) {
                ultimaFalha = e;
                if (!retentavel(e) || tentativa == maxAttempts) {
                    throw e;
                }
                logger.warn("Falha transitória no Gemini ({}), tentativa {}/{}; aguardando {} ms.",
                        e.getUpstreamStatus(), tentativa, maxAttempts, espera);
                aguardar(espera);
                espera = (long) (espera * retryMultiplier);
            }
        }

        throw ultimaFalha;
    }

    private String chamarModelo(String model, String requestBody) throws IOException {
        String token;
        try {
            token = tokenProvider.accessToken();
        } catch (IOException e) {
            throw OcrProviderException.configuration(null, e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    buildVertexUrl(model),
                    HttpMethod.POST,
                    request,
                    String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }
            throw OcrProviderException.unavailable(response.getStatusCode().value(), null);
        } catch (HttpStatusCodeException e) {
            throw traduzirErro(e, model);
        } catch (ResourceAccessException e) {
            throw OcrProviderException.network(e);
        }
    }

    private OcrProviderException traduzirErro(HttpStatusCodeException e, String model) {
        int status = e.getStatusCode().value();
        Long retryAfter = retryAfterSegundos(e.getResponseHeaders());

        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return OcrProviderException.rateLimited(retryAfter != null ? retryAfter : 60L, e);
        }
        if (status == HttpStatus.UNAUTHORIZED.value()
                || status == HttpStatus.PAYMENT_REQUIRED.value()
                || status == HttpStatus.FORBIDDEN.value()) {
            return OcrProviderException.configuration(status, e);
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return OcrProviderException.modelUnavailable(model, e);
        }
        if (status >= 500) {
            return OcrProviderException.unavailable(status, e);
        }
        return OcrProviderException.configuration(status, e);
    }

    private static Long retryAfterSegundos(HttpHeaders headers) {
        String valor = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Math.max(1L, Long.parseLong(valor.trim()));
        } catch (NumberFormatException ignored) {
            // O header também pode vir como data HTTP (RFC 7231).
            try {
                ZonedDateTime quando = ZonedDateTime.parse(valor, DateTimeFormatter.RFC_1123_DATE_TIME);
                long segundos = java.time.Duration.between(ZonedDateTime.now(quando.getZone()), quando).getSeconds();
                return Math.max(1L, segundos);
            } catch (DateTimeParseException tambemInvalido) {
                return null;
            }
        }
    }

    private static boolean retentavel(OcrProviderException e) {
        if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            return true;
        }
        Integer upstream = e.getUpstreamStatus();
        if (upstream != null) {
            return upstream >= 500;
        }
        // Falha de rede não tem status upstream; é o caso de retry puro.
        return e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE;
    }

    private void aguardar(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OcrProviderException.network(e);
        }
    }

    private String buildVertexUrl(String model) {
        String host = GLOBAL_LOCATION.equalsIgnoreCase(location)
                ? GLOBAL_HOST
                : location + "-" + GLOBAL_HOST;
        return String.format(
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                host, projectId, location, model);
    }

    private List<String> modelosParaTentar() {
        Set<String> modelos = new LinkedHashSet<>();
        modelos.add(modelName);
        modelos.addAll(fallbackModels);
        return List.copyOf(modelos);
    }

    private static List<String> parseModels(String csv) {
        if (isBlank(csv)) {
            return List.of();
        }
        Set<String> modelos = new LinkedHashSet<>();
        for (String parte : csv.split(",")) {
            String modelo = parte.trim();
            if (!modelo.isEmpty()) {
                modelos.add(modelo);
            }
        }
        return List.copyOf(modelos);
    }

    private static boolean isBlank(String valor) {
        return valor == null || valor.isBlank();
    }

    /**
     * Build JSON request body for Vertex AI
     */
    private String buildRequestBody(String base64Image, String mimeType) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode parts = objectMapper.createArrayNode();

        // Add text part (prompt)
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("text", buildExtractionPrompt());
        parts.add(textPart);

        // Add image part
        ObjectNode imagePart = objectMapper.createObjectNode();
        ObjectNode inlineData = objectMapper.createObjectNode();
        inlineData.put("mimeType", mimeType);
        inlineData.put("data", base64Image);
        imagePart.set("inlineData", inlineData);
        parts.add(imagePart);

        // O Vertex AI rejeita o content sem role explicito com HTTP 400
        // INVALID_ARGUMENT ("Please use a valid role: user, model.").
        content.put("role", "user");
        content.set("parts", parts);
        contents.add(content);
        root.set("contents", contents);

        // temperature 0 reduz variação entre execuções; responseMimeType faz o
        // modelo devolver JSON em vez de texto/markdown.
        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("temperature", 0);
        generationConfig.put("responseMimeType", "application/json");
        root.set("generationConfig", generationConfig);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Extract text from Gemini API response
     */
    private String extractTextFromResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
            JsonNode firstCandidate = candidates.get(0);
            JsonNode content = firstCandidate.get("content");
            if (content != null) {
                JsonNode parts = content.get("parts");
                if (parts != null && parts.isArray() && parts.size() > 0) {
                    JsonNode text = parts.get(0).get("text");
                    if (text != null) {
                        return text.asText();
                    }
                }
            }
        }
        throw new RuntimeException("Could not extract text from Gemini response");
    }

    /**
     * Build the extraction prompt for Gemini with instructions for split tables
     */
    private String buildExtractionPrompt() {
        return """
                You are extracting milk collection data from a table image.

                IMPORTANT: This table may be split into TWO separate images:
                - Image 1: Days 1-15 (columns for days 1, 2, 3... up to 15)
                - Image 2: Days 16-31 (columns for days 16, 17, 18... up to 31)

                Each image shows the SAME producers in the first column, but different day ranges.

                TASK: Extract all daily milk collection entries from this image.

                TABLE STRUCTURE:
                - First column: Producer name (may have leading numbers like "1. João Silva" or "1 - João Silva")
                - Subsequent columns: Day numbers (1-15 OR 16-31) with volume in liters
                - Column headers may show "DIA 01", "DIA 02" or just "01", "02", etc.

                RULES:
                1. Clean producer names: Remove leading numbers, dots, dashes, and extra spaces
                2. Extract only integer volumes (ignore dashes, empty cells, "0", non-numeric text)
                3. Skip rows containing "TOTAL", "MÉDIA", "MEDIA", or header text
                4. Match day numbers from column headers (1-15 or 16-31 depending on the image)
                5. Return ONLY a valid JSON array with: produtorNome, dia, quantidadeLitros

                EXAMPLE OUTPUT:
                [
                  {"produtorNome": "João Silva", "dia": 1, "quantidadeLitros": 150},
                  {"produtorNome": "João Silva", "dia": 2, "quantidadeLitros": 142},
                  {"produtorNome": "Maria", "dia": 1, "quantidadeLitros": 98}
                ]

                NOTE: Only include entries where quantidadeLitros > 0. Ignore empty cells and dashes.
                Return ONLY the JSON array, no additional text or explanation.
                """;
    }

    /**
     * Parse Gemini's JSON response and map to CollectionExtractedDto objects
     */
    private List<CollectionExtractedDto> parseGeminiResponse(String responseText, int year, int month) {
        List<CollectionExtractedDto> coletas = new ArrayList<>();

        try {
            // Clean response - remove markdown code blocks if present
            String cleanedJson = responseText.trim();
            if (cleanedJson.startsWith("```json")) {
                cleanedJson = cleanedJson.substring(7);
            }
            if (cleanedJson.startsWith("```")) {
                cleanedJson = cleanedJson.substring(3);
            }
            if (cleanedJson.endsWith("```")) {
                cleanedJson = cleanedJson.substring(0, cleanedJson.length() - 3);
            }
            cleanedJson = cleanedJson.trim();

            // Parse JSON array
            JsonNode rootNode = objectMapper.readTree(cleanedJson);

            if (!rootNode.isArray()) {
                logger.warn("Gemini response is not a JSON array, attempting to extract array");
                // Try to find array in response
                if (rootNode.has("data") && rootNode.get("data").isArray()) {
                    rootNode = rootNode.get("data");
                } else {
                    logger.error("Could not find valid JSON array in response");
                    return coletas;
                }
            }

            // Process each entry
            for (JsonNode node : rootNode) {
                try {
                    String produtorNome = node.get("produtorNome").asText();
                    int dia = node.get("dia").asInt();
                    int quantidadeLitros = node.get("quantidadeLitros").asInt();

                    // Validate data
                    if (dia < 1 || dia > 31 || quantidadeLitros <= 0) {
                        logger.debug("Skipping invalid entry: dia={}, volume={}", dia, quantidadeLitros);
                        continue;
                    }

                    LocalDate data;
                    try {
                        data = LocalDate.of(year, month, dia);
                    } catch (Exception dateEx) {
                        logger.warn("Invalid date: year={}, month={}, day={}", year, month, dia);
                        continue;
                    }

                    // Match por nome, restrito à organização de quem está
                    // importando. Sem o escopo, a leitura por imagem (que pode
                    // trazer o nome com erro de digitação) casaria com produtor
                    // de outra organização. A ordenação por tamanho do nome
                    // favorece a correspondência mais exata.
                    List<Supplier> candidatos = produtorRepository
                            .findVisiveisPorNome(produtorNome, organizacaoContexto.organizacaoId());
                    Optional<Supplier> produtorOpt = candidatos.isEmpty()
                            ? Optional.empty()
                            : Optional.of(candidatos.get(0));

                    if (produtorOpt.isPresent()) {
                        coletas.add(CollectionExtractedDto.matched(produtorNome, produtorOpt.get().getId(), data,
                                quantidadeLitros));
                        logger.debug("Mapped: {} (ID: {}) -> Day {} = {} liters",
                                produtorNome, produtorOpt.get().getId(), dia, quantidadeLitros);
                    } else {
                        // B17: previously logged and silently dropped. Now surfaced to the caller
                        // (matched=false, produtorId=null) for manual review instead of losing data.
                        logger.warn("Producer not found in database: '{}'", produtorNome);
                        coletas.add(CollectionExtractedDto.unmatched(produtorNome, data, quantidadeLitros));
                    }

                } catch (Exception e) {
                    logger.warn("Error parsing entry: {}", node, e);
                }
            }

        } catch (Exception e) {
            logger.error("Error parsing Gemini JSON response", e);
            logger.error("Response text: {}", responseText);
        }

        return coletas;
    }

    @Override
    public String getServiceName() {
        return "Google Gemini Vertex AI (" + modelName + ")";
    }

    /**
     * Confere se o Vertex AI está configurado: projeto, localização e ADC.
     * O projeto não tem default no código porque precisa ser o mesmo do
     * billing/Vertex; em produção o deploy injeta GEMINI_PROJECT_ID.
     */
    public boolean isConfigured() {
        return !isBlank(projectId)
                && !projectId.toLowerCase().contains("undefined")
                && !isBlank(location)
                && tokenProvider != null
                && tokenProvider.isConfigured();
    }
}
