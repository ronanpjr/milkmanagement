package com.erp.ocr.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlySettlementResponseDto(
                Long id,
                int mes,
                int ano,
                LocalDate dataInicio,
                LocalDate dataFim,
                int totalLitros,
                BigDecimal precoLitro,
                BigDecimal totalBruto,
                BigDecimal descontos,
                java.util.List<DeductionDto> listaDeductions,
                BigDecimal totalLiquido,
                String statusPagamento,
                LocalDate dataPagamento,
                Long produtorId,
                String produtorNome,
                BigDecimal cbt,
                BigDecimal ccs,
                BigDecimal gordura,
                BigDecimal proteina,
                BigDecimal bonusVolume,
                BigDecimal bonusFidelidade,
                BigDecimal precoFinal,
                String estrategiaPreco) {
}