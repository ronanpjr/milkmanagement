package com.erp.ocr.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Entity
@Table(name = "suppliers")
@Data
@NoArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String nome;

    @Column(name = "route")
    private String linha; // Rota de coleta

    @Column(name = "payment_handle")
    private String chavePix;

    @Column(name = "payment_method")
    private String formaPagamento;

    @Column(name = "tax_id")
    private String inscricaoEstadual; // Inscrição Estadual (IE) para Nota Fiscal

    @Column(name = "active", nullable = false)
    private boolean ativo = true;

    @Column(name = "use_tiered_pricing", nullable = false)
    private boolean usaPagamentoPorQualidade = false;

    // Organização dona do produtor. É a raiz da propriedade de todo o resto
    // (coletas e fechamentos são alcançados por ele), então UMA coluna aqui
    // escopa a árvore inteira — ver V4__tenancy.sql.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    // Um produtor pode ter várias coletas diárias
    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CollectionEntry> coletas;

    // Um produtor pode ter vários fechamentos mensais
    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MonthlySettlement> fechamentos;
}