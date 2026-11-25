package com.erp.ocr.dto;

import java.time.LocalDateTime;

/**
 * Perfil do usuário autenticado, consumido pelo frontend.
 * <p>
 * O contrato é {@code { email, nome, role, organizacao }} — o mesmo nome em
 * JSON e em Java, para não haver tradução no cliente. O frontend usa `role`
 * para esconder o que é exclusivo de ADMIN (tela de Usuários), e
 * `organizacao` para mostrar a qual carteira os dados pertencem.
 */
public record UserProfileDto(
        String email,
        String nome,
        String role,
        String organizacao,
        LocalDateTime membroDesde) {
}
