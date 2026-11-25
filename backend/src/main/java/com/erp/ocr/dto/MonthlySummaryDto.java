package com.erp.ocr.dto;

import java.math.BigDecimal;

public record MonthlySummaryDto(
        Long produtorId,
        int ano,
        int mes,
        Long totalLitros // Sum returns Long
) {
}
