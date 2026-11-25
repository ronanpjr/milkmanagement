package com.erp.ocr.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionEntryDto {

    @NotNull
    private LocalDate data;

    @NotNull
    @jakarta.validation.constraints.Min(value = 0, message = "A quantidade de litros deve ser maior ou igual a zero.")
    // Sem teto, um unico lancamento podia passar de Integer.MAX_VALUE. Como a
    // soma por periodo usava int, o estouro virava total NEGATIVO e
    // contaminava totalBruto/totalLiquido do fechamento.
    @jakarta.validation.constraints.Max(value = 100_000, message = "A quantidade de litros não pode exceder 100.000 por dia.")
    private int quantidadeLitros;

    @NotNull
    private Long supplierId;
}