package com.erp.ocr.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record DeductionDto(
        Long id,
        @NotNull String descricao,
        @NotNull @Positive BigDecimal valor) {
}
