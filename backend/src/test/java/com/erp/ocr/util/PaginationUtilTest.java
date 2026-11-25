package com.erp.ocr.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobertura de {@link PaginationUtil} (B12) — o teto de 200 itens por página e
 * a lista branca de ordenação são o que impedem um cliente de pedir
 * size=5000 (carga alta no banco) ou ordenar por coluna fora do contrato.
 */
class PaginationUtilTest {

    private static final Set<String> CAMPOS_PERMITIDOS = new LinkedHashSet<>(Set.of("name", "entry_date", "id"));

    @Test
    void of_DeveMontarPageableQuandoParametrosValidos() {
        Pageable pageable = PaginationUtil.of(0, 50, Sort.by(Sort.Direction.ASC, "name"));

        assertEquals(0, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        assertEquals(Sort.by(Sort.Direction.ASC, "name"), pageable.getSort());
    }

    @Test
    void of_DeveLimitarSizeAoTetoMaximo() {
        Pageable pageable = PaginationUtil.of(3, 5000, Sort.unsorted());

        assertEquals(PaginationUtil.MAX_PAGE_SIZE, pageable.getPageSize());
        assertEquals(200, PaginationUtil.MAX_PAGE_SIZE);
        assertEquals(3, pageable.getPageNumber());
    }

    @Test
    void of_DeveManterSizeExatamenteNoTeto() {
        Pageable pageable = PaginationUtil.of(0, 200, Sort.unsorted());

        assertEquals(200, pageable.getPageSize());
    }

    @Test
    void of_DeveAceitarSizeAcimaDoTetoMenosUmSemLimitar() {
        Pageable pageable = PaginationUtil.of(0, 199, Sort.unsorted());

        assertEquals(199, pageable.getPageSize());
    }

    @Test
    void of_DeveRejeitarPageNegativo() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.of(-1, 10, Sort.unsorted()));

        assertEquals("O parâmetro 'page' não pode ser negativo.", erro.getMessage());
    }

    @Test
    void of_DeveRejeitarSizeZero() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.of(0, 0, Sort.unsorted()));

        assertEquals("O parâmetro 'size' deve ser maior que zero.", erro.getMessage());
    }

    @Test
    void of_DeveRejeitarSizeNegativo() {
        assertThrows(IllegalArgumentException.class, () -> PaginationUtil.of(0, -5, Sort.unsorted()));
    }

    @Test
    void validateSortField_DeveDevolverOCampoPedidoQuandoPermitido() {
        assertEquals("entry_date", PaginationUtil.validateSortField("entry_date", CAMPOS_PERMITIDOS));
    }

    @Test
    void validateSortField_DeveRejeitarCampoForaDaListaBranca() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.validateSortField("senhaHash", CAMPOS_PERMITIDOS));

        assertTrue(erro.getMessage().contains("Campo de ordenação inválido"));
        // A mensagem precisa listar o que é aceito, senão o cliente não sabe corrigir.
        assertTrue(erro.getMessage().contains("name"));
        assertTrue(erro.getMessage().contains("entry_date"));
        assertFalse(erro.getMessage().contains("senhaHash"));
    }

    @Test
    void validateSortField_DeveRejeitarCampoNulo() {
        assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.validateSortField(null, CAMPOS_PERMITIDOS));
    }

    @Test
    void validateSortField_DeveSerCaseSensitive() {
        // "NOME" não é "name": a lista branca é comparada exatamente, então um
        // cliente que mande maiúscula recebe 400 em vez de um ORDER BY silencioso.
        assertThrows(IllegalArgumentException.class,
                () -> PaginationUtil.validateSortField("NOME", CAMPOS_PERMITIDOS));
    }
}
