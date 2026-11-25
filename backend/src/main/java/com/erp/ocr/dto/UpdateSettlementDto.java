package com.erp.ocr.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateSettlementDto(
        // Deductions: era o unico campo monetario do DTO sem nenhum limite. Com
        // valor negativo, totalLiquido ficava MAIOR que totalBruto (o produtor
        // era creditado com dinheiro nunca descontado); sem teto, descontos
        // maiores que o bruto deixavam o total negativo. O servico ainda valida
        // descontos <= totalBruto antes de gravar.
        @DecimalMin(value = "0.00", message = "O desconto total não pode ser negativo.")
        BigDecimal descontos,
        java.util.List<DeductionDto> listaDeductions,
        // Texto livre quebrava os filtros de relatorios/UI, que comparam o
        // literal, e um valor >255 chars virava 409 de integridade. Aceita
        // apenas os status reais do dominio.
        @Size(max = 32)
        @Pattern(regexp = "Pendente|Pago|Cancelado", message = "Status de pagamento inválido. Use: Pendente, Pago ou Cancelado.")
        String statusPagamento, // Ex: "Pendente", "Pago"
        @PastOrPresent LocalDate dataPagamento,

        // Campos de preço (opccionais para edição)
        @Positive BigDecimal precoLitro,

        // Métricas de qualidade (opcionais para edição)
        BigDecimal cbt,
        BigDecimal ccs,
        BigDecimal gordura,
        BigDecimal proteina,
        @DecimalMin(value = "-10.0000", message = "O bônus de volume não pode ser menor que -10.0000.")
        @DecimalMax(value = "10.0000", message = "O bônus de volume não pode ser maior que 10.0000.")
        BigDecimal bonusVolume,
        @DecimalMin(value = "-10.0000", message = "O bônus de fidelidade não pode ser menor que -10.0000.")
        @DecimalMax(value = "10.0000", message = "O bônus de fidelidade não pode ser maior que 10.0000.")
        BigDecimal bonusFidelidade) {
}
