package com.erp.ocr.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.erp.ocr.model.OcrUsage;
import com.erp.ocr.model.OcrUsageId;

import java.time.LocalDate;
import java.util.Optional;

public interface OcrUsageRepository extends JpaRepository<OcrUsage, OcrUsageId> {

    Optional<OcrUsage> findByUserIdAndDia(Long usuarioId, LocalDate dia);

    /**
     * Leitura com lock pessimista para o consumo atômico da cota. O lock
     * serializa dois uploads do mesmo usuário no mesmo dia, evitando que
     * ambos leiam o mesmo contador e estourem o limite.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM OcrUsage u WHERE u.userId = :usuarioId AND u.dia = :dia")
    Optional<OcrUsage> findComLockByUserIdAndDia(@Param("usuarioId") Long usuarioId,
            @Param("dia") LocalDate dia);
}
