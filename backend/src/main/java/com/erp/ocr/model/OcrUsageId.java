package com.erp.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Chave composta de {@link OcrUsage}: um registro por usuário e por dia.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrUsageId implements Serializable {

    private Long userId;

    private LocalDate dia;
}
