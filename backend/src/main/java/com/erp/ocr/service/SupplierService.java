package com.erp.ocr.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.erp.ocr.dto.SupplierRequestDto;
import com.erp.ocr.dto.SupplierResponseDto;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupplierService {

    @Autowired
    private SupplierRepository produtorRepository;

    @Autowired
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    @Autowired
    private com.erp.ocr.repository.OrganizationRepository organizacaoRepository;

    /**
     * Organization a que um produtor recem-criado pertence.
     * <p>
     * USER: a propria. ADMIN, que tem escopo global: precisa apontar para uma
     * organizacao concreta, e usa a do proprio usuario (o cadastro de
     * produtor para outra organizacao e uma operacao de suporte, que deve ser
     * explicita e nao deduzida).
     */
    private com.erp.ocr.model.Organization resolveOrganizationParaNovo() {
        com.erp.ocr.model.User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario == null || usuario.getOrganization() == null) {
            throw new IllegalStateException(
                    "Não há usuário autenticado com organização definida para atribuir ao produtor.");
        }
        return usuario.getOrganization();
    }

    // Retorna uma lista de DTOs, não de entidades
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SupplierResponseDto> listarTodos(
            org.springframework.data.domain.Pageable pageable) {
        return produtorRepository.findVisiveis(organizacaoContexto.organizacaoId(), pageable)
                .map(this::toResponseDto);
    }

    // Retorna um DTO
    @Transactional(readOnly = true)
    public SupplierResponseDto buscarPorId(Long id) {
        Supplier produtor = findSupplierById(id);
        return toResponseDto(produtor);
    }

    // Recebe um DTO e retorna um DTO
    @Transactional
    public SupplierResponseDto criar(SupplierRequestDto dto) {
        Supplier novoSupplier = new Supplier();
        novoSupplier.setNome(dto.nome());
        novoSupplier.setLinha(dto.linha());
        novoSupplier.setChavePix(dto.chavePix());
        novoSupplier.setAtivo(dto.ativo());
        if (dto.usaPagamentoPorQualidade() != null) {
            novoSupplier.setUsaPagamentoPorQualidade(dto.usaPagamentoPorQualidade());
        }
        novoSupplier.setInscricaoEstadual(dto.inscricaoEstadual());
        // Sem isso o produtor nasceria sem dono e nao apareceria em lugar
        // nenhum (a coluna e NOT NULL). Para USER, a organizacao e a dele.
        novoSupplier.setOrganization(resolveOrganizationParaNovo());

        Supplier produtorSalvo = produtorRepository.save(novoSupplier);
        return toResponseDto(produtorSalvo);
    }

    // Recebe um DTO e retorna um DTO
    @Transactional
    public SupplierResponseDto atualizar(Long id, SupplierRequestDto dto) {
        Supplier produtor = findSupplierById(id);

        produtor.setNome(dto.nome());
        produtor.setLinha(dto.linha());
        produtor.setChavePix(dto.chavePix());
        produtor.setAtivo(dto.ativo());
        if (dto.usaPagamentoPorQualidade() != null) {
            produtor.setUsaPagamentoPorQualidade(dto.usaPagamentoPorQualidade());
        }
        produtor.setInscricaoEstadual(dto.inscricaoEstadual());

        Supplier produtorAtualizado = produtorRepository.save(produtor);
        return toResponseDto(produtorAtualizado);
    }

    @Transactional(readOnly = true)
    public List<SupplierResponseDto> listarSemFechamento(java.time.LocalDate inicio, java.time.LocalDate fim) {
        return produtorRepository
                .findVisiveisSemFechamento(inicio, fim, organizacaoContexto.organizacaoId()).stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deletar(Long id) {
        Supplier produtor = findSupplierById(id);
        produtorRepository.delete(produtor);
    }

    // --- Métodos Auxiliares ---

    // Método privado para buscar a entidade, reutilizado internamente
    private Supplier findSupplierById(Long id) {
        return produtorRepository.findVisivelById(id, organizacaoContexto.organizacaoId())
                .orElseThrow(() -> new EntityNotFoundException("Supplier não encontrado com o ID: " + id));
    }

    private SupplierResponseDto toResponseDto(Supplier produtor) {
        return new SupplierResponseDto(
                produtor.getId(),
                produtor.getNome(),
                produtor.getLinha(),
                produtor.getChavePix(),
                produtor.isAtivo(),
                produtor.isUsaPagamentoPorQualidade(),
                produtor.getInscricaoEstadual());
    }
}