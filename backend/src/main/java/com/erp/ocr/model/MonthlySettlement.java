package com.erp.ocr.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "monthly_settlements")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlySettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Min(value = 1, message = "O mês deve ser no mínimo 1.")
    @Max(value = 12, message = "O mês deve ser no máximo 12.")
    @Column(name = "settlement_month", nullable = false)
    private int mes;

    @Column(name = "settlement_year", nullable = false)
    private int ano;

    @Column(name = "start_date", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "end_date", nullable = false)
    private LocalDate dataFim;

    @Column(name = "total_volume", nullable = false)
    private int totalLitros;

    @Column(name = "unit_price", precision = 10, scale = 4)
    private BigDecimal precoLitro;

    @Column(name = "gross_total", precision = 10, scale = 2)
    private BigDecimal totalBruto;

    @Column(name = "deductions_total", precision = 10, scale = 2)
    private BigDecimal deductions;

    @Column(name = "net_total", precision = 10, scale = 2)
    private BigDecimal totalLiquido;

    @Column(precision = 10, scale = 4)
    private BigDecimal cbt;

    @Column(precision = 10, scale = 4)
    private BigDecimal ccs;

    @Column(precision = 5, scale = 2)
    private BigDecimal gordura;

    @Column(precision = 5, scale = 2)
    private BigDecimal proteina;

    @Column(name = "volume_bonus", precision = 10, scale = 4)
    private BigDecimal bonusVolume; // Bônus de volume definido pelo usuário

    @Column(name = "loyalty_bonus", precision = 10, scale = 4)
    private BigDecimal bonusFidelidade; // Bônus de fidelidade

    @Column(name = "final_price", precision = 10, scale = 4)
    private BigDecimal precoFinal; // Preço por litro com bônus de qualidade já aplicados

    @Column(name = "payment_status")
    private String statusPagamento; // Ex: "Pendente", "Pago"

    @Column(name = "payment_date")
    private LocalDate dataPagamento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    // B13 (N+1): batch-fetch descontos for a whole page of fechamentos in one
    // extra query instead of one query per row (a collection JOIN FETCH can't be
    // combined with Pageable without breaking SQL-level pagination).
    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<Deduction> listaDeductions = new java.util.ArrayList<>();
}