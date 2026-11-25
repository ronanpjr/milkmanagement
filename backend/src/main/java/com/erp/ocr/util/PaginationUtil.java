package com.erp.ocr.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Helper compartilhado para validar/limitar parâmetros de paginação (B12).
 * Sem limite, um cliente poderia solicitar páginas arbitrariamente grandes
 * (ex.: size=5000), gerando carga alta no banco.
 */
public final class PaginationUtil {

    /** Tamanho máximo de página permitido em qualquer listagem paginada. */
    public static final int MAX_PAGE_SIZE = 200;

    private PaginationUtil() {
    }

    /**
     * Constrói um {@link Pageable}, validando {@code page >= 0} e
     * {@code size > 0}, e limitando {@code size} a {@link #MAX_PAGE_SIZE}
     * (o excesso é silenciosamente descartado/clampado, não honrado).
     *
     * @throws IllegalArgumentException se page ou size forem inválidos
     *                                  (mapeado para 400 pelo
     *                                  GlobalExceptionHandler)
     */
    public static Pageable of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new IllegalArgumentException("O parâmetro 'page' não pode ser negativo.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("O parâmetro 'size' deve ser maior que zero.");
        }
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(page, cappedSize, sort);
    }

    /**
     * Valida o campo de ordenação contra uma lista branca.
     * <p>
     * {@code Sort.by(campo)} com campo inexistente não é injeção (o Spring
     * Data valida contra a entidade e gera query parametrizada), mas a exceção
     * que ele lança ({@code PropertyReferenceException}) caía no handler
     * genérico e virava <b>500</b> em vez de um 400 de parâmetro inválido. A
     * lista branca também impede o cliente de ordenar por colunas fora do
     * contrato público do endpoint.
     *
     * @param sortBy        campo pedido na requisição
     * @param allowedFields campos aceitos por este endpoint
     * @return o campo validado
     */
    public static String validateSortField(String sortBy, Set<String> allowedFields) {
        if (sortBy == null || !allowedFields.contains(sortBy)) {
            throw new IllegalArgumentException(
                    "Campo de ordenação inválido. Use um destes: " + String.join(", ", allowedFields) + ".");
        }
        return sortBy;
    }
}
