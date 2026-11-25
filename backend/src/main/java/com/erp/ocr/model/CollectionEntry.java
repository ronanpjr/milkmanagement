package com.erp.ocr.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "collection_entries", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "supplier_id", "entry_date" })
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_date", nullable = false)
    private LocalDate data;

    @Column(name = "quantity", nullable = false)
    private int quantidadeLitros;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;
}