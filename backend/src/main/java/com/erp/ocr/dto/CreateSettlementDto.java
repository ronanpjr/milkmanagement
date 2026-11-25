package com.erp.ocr.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

import java.time.LocalDate;

public record CreateSettlementDto(
                @NotNull Long produtorId,

                @NotNull LocalDate dataInicio,

                @NotNull LocalDate dataFim,

                @NotNull @Positive BigDecimal precoLitro,

                BigDecimal cbt,
                BigDecimal ccs,
                BigDecimal gordura,
                BigDecimal proteina,
                // Os bonuses eram livres: um bonusVolume/bonusFidelidade muito
                // negativo fazia precoFinal ficar negativo e, dai, totalBruto e
                // totalLiquido negativos gravados num fechamento real. O teto
                // largo abaixo mantem folga para o uso legitimo (tabela de bonus
                // vai de -0.08 a +0.15 por litro) e so barra valores absurdos;
                // MonthlySettlementService ainda exige precoFinal > 0.
                @DecimalMin(value = "-10.0000", message = "O bônus de volume não pode ser menor que -10.0000.")
                @DecimalMax(value = "10.0000", message = "O bônus de volume não pode ser maior que 10.0000.")
                BigDecimal bonusVolume,
                @DecimalMin(value = "-10.0000", message = "O bônus de fidelidade não pode ser menor que -10.0000.")
                @DecimalMax(value = "10.0000", message = "O bônus de fidelidade não pode ser maior que 10.0000.")
                BigDecimal bonusFidelidade) {
}
