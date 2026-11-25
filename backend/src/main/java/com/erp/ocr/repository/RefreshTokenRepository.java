package com.erp.ocr.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.erp.ocr.model.RefreshToken;

import java.util.List;
import java.util.Optional;

/**
 * Persistência dos refresh tokens (achado 12 — rotação com estado no banco).
 * <p>
 * A busca é sempre pelo HASH do token, nunca pelo valor em claro. A listagem
 * por usuário/ativo sustenta a revogação em massa na detecção de reuso.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevogadoEmIsNull(Long usuarioId);
}
