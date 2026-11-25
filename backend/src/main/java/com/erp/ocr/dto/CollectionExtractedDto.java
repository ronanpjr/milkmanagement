package com.erp.ocr.dto;

import java.time.LocalDate;

/**
 * Resultado de uma linha extraída via OCR de upload em lote (B17).
 * <p>
 * Diferente de {@link CollectionEntryDto}, permite representar linhas cujo
 * produtor não foi identificado no cadastro ({@code matched=false},
 * {@code produtorId=null}) para conferência manual do usuário, em vez de
 * descartá-las silenciosamente como acontecia antes.
 */
public record CollectionExtractedDto(
        String produtorNomeDetectado,
        Long produtorId,
        boolean matched,
        LocalDate data,
        Integer quantidadeLitros) {

    public static CollectionExtractedDto matched(String produtorNomeDetectado, Long produtorId, LocalDate data,
            Integer quantidadeLitros) {
        return new CollectionExtractedDto(produtorNomeDetectado, produtorId, true, data, quantidadeLitros);
    }

    public static CollectionExtractedDto unmatched(String produtorNomeDetectado, LocalDate data,
            Integer quantidadeLitros) {
        return new CollectionExtractedDto(produtorNomeDetectado, null, false, data, quantidadeLitros);
    }
}
