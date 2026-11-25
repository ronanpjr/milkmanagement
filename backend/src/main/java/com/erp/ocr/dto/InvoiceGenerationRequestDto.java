package com.erp.ocr.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InvoiceGenerationRequestDto(
        @NotNull(message = "Supplier ID is required")
        Long supplierId,

        @NotNull(message = "Year is required")
        @Min(value = 2000, message = "Year must be >= 2000")
        Integer year,

        @NotNull(message = "Month is required")
        @Min(value = 1, message = "Month must be between 1 and 12")
        @Max(value = 12, message = "Month must be between 1 and 12")
        Integer month,

        Boolean useSimulatedData,

        @Min(value = 1, message = "Collection days must be >= 1")
        @Max(value = 31, message = "Collection days must be <= 31")
        Integer simulatedDaysCount,

        @Min(value = 0, message = "Minimum quantity must be >= 0")
        Integer minQuantity,

        Integer maxQuantity
) {
    public boolean isUseSimulatedData() {
        return Boolean.TRUE.equals(useSimulatedData);
    }

    public int getSimulatedDaysCountOrDefault() {
        return simulatedDaysCount != null ? simulatedDaysCount : 15;
    }

    public int getMinQuantityOrDefault() {
        return minQuantity != null ? minQuantity : 100;
    }

    public int getMaxQuantityOrDefault() {
        return maxQuantity != null ? maxQuantity : 500;
    }
}
