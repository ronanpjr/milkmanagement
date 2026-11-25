package com.erp.ocr.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;

/**
 * Organização do usuário autenticado, resolvida do contexto de segurança.
 * <p>
 * Concentrar isto aqui evita que cada service leia o SecurityContext e
 * repita a lógica de "ADMIN vê tudo, USER só a própria".
 * <p>
 * O {@code User} precisa chegar aqui com a organização carregada
 * (lazy não sobrevive fora da transação que o carregou) — por isso o
 * SecurityFilter busca com join fetch.
 */
@Component
public class OrganizationContext {

    /**
     * Organização do usuário autenticado, ou {@code null} para ADMIN (que vê
     * todas) e para usuário sem autenticação. Os repositories tratam
     * {@code null} como "sem filtro de organização".
     *
     * @return id da organização, ou null se não houver filtro a aplicar
     */
    public Long organizacaoId() {
        User usuario = usuarioAutenticado();
        if (usuario == null) {
            return null;
        }
        // ADMIN não é filtrado: enxerga todas as organizações.
        if (usuario.getRole() == Role.ADMIN) {
            return null;
        }
        if (usuario.getOrganization() == null) {
            // Não deveria acontecer (a coluna é NOT NULL), mas falhar aqui é
            // melhor do que devolver null e o chamador tratar como "sem filtro",
            // o que daria acesso a tudo.
            throw new IllegalStateException(
                    "Usuário autenticado sem organização definida: " + usuario.getEmail()
                            + ". Isso indicaria registro criado fora do fluxo com organização.");
        }
        return usuario.getOrganization().getId();
    }

    /** @return true quando o usuário autenticado é ADMIN (escopo global). */
    public boolean isAdmin() {
        User usuario = usuarioAutenticado();
        return usuario != null && usuario.getRole() == Role.ADMIN;
    }

    /** @return true quando há filtro de organização a aplicar. */
    public boolean temEscopo() {
        return organizacaoId() != null;
    }

    /**
     * Usuário autenticado no contexto de segurança, ou null.
     * <p>
     * Público porque services precisam do {@code User} completo (e não só
     * do id da organização) para atribuir propriedade de registros novos.
     *
     * @return o usuário autenticado, ou null se não houver
     */
    public User usuarioAutenticado() {
        return usuarioAutenticadoDoContexto();
    }

    private User usuarioAutenticadoDoContexto() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof User usuario) {
            return usuario;
        }
        // "anonymousUser" e afins não são User.
        return null;
    }
}
