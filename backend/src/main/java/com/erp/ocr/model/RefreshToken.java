package com.erp.ocr.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Refresh token persistido, base da rotação com estado no banco (achado 12).
 * <p>
 * O que fica gravado é o HASH SHA-256 do token, nunca o token em claro: um
 * vazamento do banco não entrega sessões utilizáveis. A linha é a fonte da
 * verdade para revogação (logout e reuse-detection) e expiração.
 */
@Entity
@Table(name = "refresh_tokens")
@Data
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime criadoEm;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiraEm;

    @Column(name = "revoked_at")
    private LocalDateTime revogadoEm;

    /** Sessão ainda válida: não revogada (a expiração é conferida à parte). */
    public boolean isAtivo() {
        return revogadoEm == null;
    }

    public boolean isExpirado(LocalDateTime agora) {
        return expiraEm.isBefore(agora);
    }
}
