package com.erp.ocr.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Organização (tenant). Todos os dados do sistema pertencem a uma.
 * <p>
 * Vários usuários compartilham a mesma organização, e portanto a mesma
 * carteira de produtores — é isso que distingue este modelo do "dono por
 * usuário": duas pessoas do mesmo time veem os mesmos dados.
 */
@Entity
@Table(name = "organizations")
@Data
@NoArgsConstructor
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    private String nome;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime criadoEm = LocalDateTime.now();

    @OneToMany(mappedBy = "organization")
    private List<User> usuarios;

    @OneToMany(mappedBy = "organization")
    private List<Supplier> produtores;

    /** Construtor de criação: só o nome importa; o id e o timestamp são do banco. */
    public Organization(String nome) {
        this.nome = nome;
    }
}
