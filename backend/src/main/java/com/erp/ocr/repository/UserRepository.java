package com.erp.ocr.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.erp.ocr.model.User;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /**
     * Busca o usuário com a organização carregada (join fetch).
     * <p>
     * Usada pelo SecurityFilter: o filtro roda fora de transação, então um
     * proxy lazy em {@code organizacao} estouraria ao ser lido depois por
     * {@code OrganizationContext}.
     */
    @Query("SELECT u FROM User u JOIN FETCH u.organization WHERE u.email = :email")
    Optional<User> findByEmailComOrganization(@Param("email") String email);

    /** Usuários de uma organização — para a tela de administração. */
    @Query("SELECT u FROM User u WHERE u.organization.id = :organizacaoId ORDER BY u.nome")
    java.util.List<User> findByOrganizationId(@Param("organizacaoId") Long organizacaoId);
}
