package com.erp.ocr.dto;

public record SupplierResponseDto(
        Long id,
        String nome,
        String linha,
        String chavePix,
        boolean ativo,
        boolean usaPagamentoPorQualidade,
        String inscricaoEstadual) {
}