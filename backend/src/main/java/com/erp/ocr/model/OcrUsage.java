package com.erp.ocr.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Consumo diário de OCR por usuário (B5).
 * <p>
 * A cota é contada por dia no fuso configurado em {@code ocr.quota.zone}. O
 * registro é criado na primeira utilização do dia e incrementado a cada
 * upload aceito; a linha {@code (usuario_id, dia)} é a chave primária, então o
 * banco garante que dois uploads simultâneos não criem contadores paralelos.
 */
@Entity
@Table(name = "ocr_usage")
@IdClass(OcrUsageId.class)
@Data
@NoArgsConstructor
public class OcrUsage {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "usage_date", nullable = false)
    private LocalDate dia;

    @Column(name = "usage_count", nullable = false)
    private int contagem;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime atualizadoEm;
}
