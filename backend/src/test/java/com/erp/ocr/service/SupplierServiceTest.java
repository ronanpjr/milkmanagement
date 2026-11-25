package com.erp.ocr.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.erp.ocr.dto.SupplierRequestDto;
import com.erp.ocr.dto.SupplierResponseDto;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupplierServiceTest {

    @InjectMocks
    private SupplierService produtorService;

    @Mock
    private SupplierRepository produtorRepository;

    @Mock
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    @Mock
    private com.erp.ocr.repository.OrganizationRepository organizacaoRepository;

    private final Long orgId = null;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(organizacaoContexto.organizacaoId()).thenReturn(orgId);
    }

    @Test
    void criar_DeveSalvarSupplier() {
        // criar() atribui a organizacao de quem criou, entao o contexto precisa
        // devolver um User com organizacao.
        com.erp.ocr.model.User usuario = new com.erp.ocr.model.User();
        usuario.setEmail("u@teste.com");
        usuario.setOrganization(new com.erp.ocr.model.Organization("Org Teste"));
        when(organizacaoContexto.usuarioAutenticado()).thenReturn(usuario);
        SupplierRequestDto dto = new SupplierRequestDto("Nome Teste", "Linha 1", "pix", true, false, null);
        Supplier produtorSalvo = produtor(1L, "Nome Teste");
        produtorSalvo.setChavePix("pix");
        produtorSalvo.setFormaPagamento("Dinheiro");

        when(produtorRepository.save(any(Supplier.class))).thenReturn(produtorSalvo);

        SupplierResponseDto response = produtorService.criar(dto);

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("Nome Teste", response.nome());
        verify(produtorRepository).save(any(Supplier.class));
    }

    @Test
    void buscarPorId_DeveRetornarSupplier_QuandoExistir() {
        Long id = 1L;
        Supplier produtor = produtor(id, "Nome");
        when(produtorRepository.findVisivelById(id, orgId)).thenReturn(Optional.of(produtor));

        SupplierResponseDto response = produtorService.buscarPorId(id);

        assertEquals(id, response.id());
    }

    @Test
    void buscarPorId_DeveLancarExcecao_QuandoNaoExistir() {
        Long id = 99L;
        when(produtorRepository.findVisivelById(id, orgId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> produtorService.buscarPorId(id));
    }

    @Test
    void listarSemFechamento_DeveRetornarSupplieres() {
        java.time.LocalDate inicio = java.time.LocalDate.now().minusDays(30);
        java.time.LocalDate fim = java.time.LocalDate.now();
        Supplier p1 = produtor(1L, "P1");

        when(produtorRepository.findVisiveisSemFechamento(inicio, fim, orgId)).thenReturn(java.util.Arrays.asList(p1));

        List<SupplierResponseDto> result = produtorService.listarSemFechamento(inicio, fim);

        assertEquals(1, result.size());
        assertEquals("P1", result.get(0).nome());
        verify(produtorRepository).findVisiveisSemFechamento(inicio, fim, orgId);
    }

    // --- helpers ---

    private static Supplier produtor(Long id, String nome) {
        Supplier p = new Supplier();
        p.setId(id);
        p.setNome(nome);
        p.setAtivo(true);
        p.setOrganization(new com.erp.ocr.model.Organization("Org Teste"));
        return p;
    }
}
