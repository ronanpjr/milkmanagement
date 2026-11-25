package com.erp.ocr.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CollectionBatchItemDto(
                @NotNull Long produtorId,

                @NotNull LocalDate data,

                @NotNull
                @Min(value = 0, message = "A quantidade de litros deve ser um valor igual ou maior que zero.")
                @Max(value = 100_000, message = "A quantidade de litros não pode exceder 100.000 por dia.")
                int quantidadeLitros) {
}
