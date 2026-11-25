package com.erp.ocr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SupplierRequestDto(
        @NotBlank(message = "O nome não pode estar em branco") String nome,

        String linha,

        String chavePix,

        @NotNull(message = "O status 'ativo' é obrigatório") Boolean ativo,

        Boolean usaPagamentoPorQualidade,

        String inscricaoEstadual) {
}