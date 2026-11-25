package com.erp.ocr.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.erp.ocr.model.Organization;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findByNome(String nome);
}
