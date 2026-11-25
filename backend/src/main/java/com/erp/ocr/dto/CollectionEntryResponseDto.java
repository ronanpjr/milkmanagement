package com.erp.ocr.dto;

import java.time.LocalDate;

public record CollectionEntryResponseDto(
        Long id,
        LocalDate data,
        int quantidadeLitros,
        Long produtorId
) {}