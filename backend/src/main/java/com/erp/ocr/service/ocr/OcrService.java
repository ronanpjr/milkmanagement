package com.erp.ocr.service.ocr;

import org.springframework.web.multipart.MultipartFile;
import com.erp.ocr.dto.CollectionExtractedDto;

import java.io.IOException;
import java.util.List;

/**
 * Strategy interface for OCR services that extract milk collection data from
 * table images.
 * Implementations can use different OCR providers (Azure, Gemini, etc.)
 */
public interface OcrService {

    /**
     * Extract batch milk collection data from an uploaded image file.
     *
     * @param file MultipartFile containing the table image (JPG, PNG, PDF, etc.)
     * @param ano  Year the sheet refers to; when null, defaults to last month's
     *             year (B17: previously always assumed "last month", which
     *             produced wrong dates for old sheets).
     * @param mes  Month (1-12) the sheet refers to; when null, defaults to last
     *             month's month.
     * @return List of CollectionExtractedDto - includes entries whose producer name
     *         couldn't be matched (matched=false, produtorId=null) instead of
     *         silently dropping them, so the caller can review them manually.
     * @throws IOException if file processing fails
     */
    List<CollectionExtractedDto> extractBatchColetas(MultipartFile file, Integer ano, Integer mes) throws IOException;

    /**
     * Get the name of this OCR service for logging and identification.
     * 
     * @return Service name (e.g., "Azure Document Intelligence", "Google Gemini")
     */
    String getServiceName();
}
