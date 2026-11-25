package com.erp.ocr.service;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.ai.documentintelligence.models.*;
import com.azure.core.credential.AzureKeyCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.erp.ocr.dto.CollectionExtractedDto;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AzureOcrService implements com.erp.ocr.service.ocr.OcrService {

    private static final Logger logger = LoggerFactory.getLogger(AzureOcrService.class);

    private final String endpoint;
    private final String key;
    private final SupplierRepository produtorRepository;
    private final com.erp.ocr.config.OrganizationContext organizacaoContexto;

    @Autowired
    public AzureOcrService(
            @Value("${azure.document-intelligence.endpoint}") String endpoint,
            @Value("${azure.document-intelligence.key}") String key,
            SupplierRepository produtorRepository,
            com.erp.ocr.config.OrganizationContext organizacaoContexto) {
        this.endpoint = endpoint;
        this.key = key;
        this.produtorRepository = produtorRepository;
        this.organizacaoContexto = organizacaoContexto;
    }

    @Override
    public List<CollectionExtractedDto> extractBatchColetas(MultipartFile file, Integer ano, Integer mes)
            throws IOException {
        logger.info("Starting batch extraction for file: {}", file.getOriginalFilename());

        try {
            DocumentIntelligenceClient client = new DocumentIntelligenceClientBuilder()
                    .credential(new AzureKeyCredential(key))
                    .endpoint(endpoint)
                    .buildClient();

            byte[] fileBytes = file.getBytes();

            // In 1.0.6, we use AnalyzeDocumentOptions to carry the payload
            AnalyzeDocumentOptions options = new AnalyzeDocumentOptions(fileBytes);

            AnalyzeResult analyzeResult = client.beginAnalyzeDocument(
                    "prebuilt-layout",
                    options)
                    .getFinalResult();

            List<CollectionExtractedDto> coletas = new ArrayList<>();
            // B17: explicit ano/mes param instead of always assuming "last month",
            // which produced wrong dates when importing an old sheet.
            int targetYear;
            int targetMonth;
            if (ano != null && mes != null) {
                targetYear = ano;
                targetMonth = mes;
            } else {
                LocalDate lastMonthDate = LocalDate.now().minusMonths(1);
                targetYear = lastMonthDate.getYear();
                targetMonth = lastMonthDate.getMonthValue();
            }

            if (analyzeResult.getTables() != null) {
                for (DocumentTable table : analyzeResult.getTables()) {
                    parseTable(table, coletas, targetYear, targetMonth);
                }
            }

            return coletas;

        } catch (Exception e) {
            logger.error("Error during Azure OCR extraction", e);
            throw new RuntimeException("Failed to process document: " + e.getMessage(), e);
        }
    }

    private void parseTable(DocumentTable table, List<CollectionExtractedDto> coletas, int year, int month) {
        logger.info("Processing table with {} rows and {} columns", table.getRowCount(), table.getColumnCount());

        int produtorColumnIndex = -1;
        int explicitSupplierIndex = -1;
        int fallbackSupplierIndex = -1;

        // Map Column Index -> Day (1..31)
        java.util.Map<Integer, Integer> columnDayMap = new java.util.HashMap<>();

        // 1. Analyze Header Rows (Check rows 0 to 4 to find the header)
        for (DocumentTableCell cell : table.getCells()) {
            if (cell.getRowIndex() <= 4) {
                String content = cell.getContent().trim();
                logger.info("Header Candidate R{}C{}: '{}'", cell.getRowIndex(), cell.getColumnIndex(), content);

                if (content.isEmpty())
                    continue;

                boolean isExplicit = content.toLowerCase().contains("forne") ||
                        content.toLowerCase().contains("produt") ||
                        content.toLowerCase().contains("name");

                if (isExplicit) {
                    explicitSupplierIndex = cell.getColumnIndex();
                    logger.info("Found EXPLICIT Supplier header at R{}C{}: '{}'", cell.getRowIndex(),
                            cell.getColumnIndex(), content);
                } else if (fallbackSupplierIndex == -1 &&
                        (!content.toUpperCase().startsWith("DIA") && cell.getColumnIndex() <= 1
                                && !content.isEmpty())) {
                    fallbackSupplierIndex = cell.getColumnIndex();
                }

                // Detect Date Columns
                // Case 1: "DIA 01"
                // Case 2: Just "01" or "1" (common in OCR errors or specific layouts)
                int day = -1;
                if (content.toUpperCase().startsWith("DIA")) {
                    try {
                        day = Integer.parseInt(content.replaceAll("[^0-9]", ""));
                    } catch (Exception e) {
                    }
                } else if (content.matches("^\\d+$")) {
                    // Only consider pure numbers as days if they are > column 1 (to avoid picking
                    // up row numbers)
                    if (cell.getColumnIndex() > 1) {
                        try {
                            day = Integer.parseInt(content);
                        } catch (Exception e) {
                        }
                    }
                }

                if (day >= 1 && day <= 31) {
                    columnDayMap.put(cell.getColumnIndex(), day);
                }
            }
        }

        // Decide Supplier Column
        if (explicitSupplierIndex != -1) {
            produtorColumnIndex = explicitSupplierIndex;
            logger.info("Using EXPLICIT Supplier column: {}", produtorColumnIndex);
        } else if (fallbackSupplierIndex != -1) {
            produtorColumnIndex = fallbackSupplierIndex;
            logger.warn("Using FALLBACK Supplier column: {}", produtorColumnIndex);
        } else {
            produtorColumnIndex = 0;
            logger.warn("Supplier column not detected, defaulting to 0");
        }

        logger.info("Mapped {} day columns: {}", columnDayMap.size(), columnDayMap);

        // Iterate rows
        for (int r = 0; r < table.getRowCount(); r++) {
            Long produtorId = null;
            String currentRowProducerName = "";
            // B17: name attempted for matching, kept even on failure so an unmatched row
            // can still be surfaced for manual review instead of silently dropped.
            String attemptedName = "";

            // 1. Find Producer for this row
            for (DocumentTableCell cell : table.getCells()) {
                if (cell.getRowIndex() == r && cell.getColumnIndex() == produtorColumnIndex) {
                    String raw = cell.getContent();
                    // Ignore "TOTAL", "MÉDIA", or Header-like rows found late
                    if (raw.toUpperCase().contains("TOTAL") ||
                            raw.toUpperCase().contains("MÉDIA") ||
                            raw.toUpperCase().contains("DIA") ||
                            (raw.toLowerCase().contains("forne") && r <= 4) || // Ignore header row itself if explicit
                            (raw.toLowerCase().contains("produt") && r <= 4)) {

                        logger.info("Skipping Non-Data row at index {} (Content: '{}')", r, raw);
                        currentRowProducerName = "SKIP";
                        break;
                    }

                    // Cleanup: Remove leading numbers like "1 ", "1.", "1-", "1 "
                    // Regex: Start with digits, optional dot/dash/space, then anything
                    String clean = raw.replaceAll("^\\d+\\s*[\\.\\-]?\\s*", "").trim();
                    // Further cleanup (keep letters and spaces)
                    clean = clean.replaceAll("[^a-zA-ZÀ-ÿ ]", "").trim();

                    if (!clean.isEmpty() && clean.length() > 2) {
                        attemptedName = clean;
                        // Escopado pela organização de quem importa: sem isso, o
                        // nome lido da imagem poderia casar com produtor de outra.
                        List<Supplier> achados = produtorRepository
                                .findVisiveisPorNome(clean, organizacaoContexto.organizacaoId());
                        Optional<Supplier> p = achados.isEmpty() ? Optional.empty() : Optional.of(achados.get(0));
                        if (p.isPresent()) {
                            produtorId = p.get().getId();
                            currentRowProducerName = p.get().getNome();
                            logger.info("Row {}: Match '{}' -> ID {}", r, clean, produtorId);
                        } else {
                            logger.warn("Row {}: No match for '{}'", r, clean);
                        }
                    }
                }
            }

            // Skip header/total rows and rows with no plausible producer name at all.
            // A plausible name that just didn't match (attemptedName set, produtorId
            // null) is NOT skipped - it's surfaced as unmatched below.
            if ("SKIP".equals(currentRowProducerName) || attemptedName.isEmpty())
                continue;

            boolean matched = produtorId != null;
            String produtorNomeDetectado = matched ? currentRowProducerName : attemptedName;

            // 2. Extract Volumes using Property Map
            for (DocumentTableCell cell : table.getCells()) {
                if (cell.getRowIndex() == r && columnDayMap.containsKey(cell.getColumnIndex())) {
                    Integer day = columnDayMap.get(cell.getColumnIndex());
                    String content = cell.getContent().trim();

                    // Strict Integer-Only Filter (User Request: "no dashes or commas, only
                    // integers")
                    // Reject if contains anything other than digits and spaces.
                    if (!content.matches("^[0-9\\s]+$")) {
                        continue;
                    }

                    // Clean content
                    String volStr = content.replaceAll("[^0-9]", "");

                    if (!volStr.isEmpty()) {
                        try {
                            int volume = Integer.parseInt(volStr);
                            if (volume > 0) {
                                try {
                                    LocalDate data = LocalDate.of(year, month, day);
                                    coletas.add(matched
                                            ? CollectionExtractedDto.matched(produtorNomeDetectado, produtorId, data,
                                                    volume)
                                            : CollectionExtractedDto.unmatched(produtorNomeDetectado, data, volume));
                                } catch (Exception dex) {
                                    // Invalid date
                                }
                            }
                        } catch (NumberFormatException nfe) {
                            // ignore
                        }
                    }
                }
            }
        }
    }

    @Override
    public String getServiceName() {
        return "Azure Document Intelligence";
    }
}
