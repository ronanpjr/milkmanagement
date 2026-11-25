package com.erp.ocr.isolation;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.dto.CollectionEntryDto;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.service.CollectionEntryService;
import com.erp.ocr.service.MonthlySettlementService;
import com.erp.ocr.service.SupplierService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolamento entre organizações.
 *
 * <p>
 * Este é o teste que sustenta a fase de tenant. Sem ele, qualquer finder
 * novo sem filtro de organização passa despercebido — e vazamento de dado
 * entre carteiras de produtores diferentes não gera erro, apenas um resultado
 * errado na tela de quem não devia enxergar aquilo.
 *
 * <p>
 * Regra: organização errada responde <b>404</b>, nunca 403 — o 403 confirmaria
 * a existência do registro, que é informação de outra organização.
 *
 * <p>
 * Usa @SpringBootTest (e não mocks) de propósito: a validação de que o JPQL
 * filtra de fato é o que importa, e um mock passaria mesmo com a query errada.
 */
@SpringBootTest
@Transactional
@DisplayName("Isolamento de dados por organização")
class OrganizationIsolationTest {

    // As OCR services exigem ADC/projeto do Vertex, que não existem no profile
    // de teste, e sobem antes de qualquer repositório ser usado. Não participam
    // deste teste, então ficam mockadas.
    @MockitoBean
    private com.erp.ocr.service.ocr.GeminiOcrService geminiOcrService;
    @MockitoBean
    private com.erp.ocr.service.AzureOcrService azureOcrService;

    @Autowired
    private SupplierRepository produtorRepository;
    @Autowired
    private CollectionEntryRepository coletaRepository;
    @Autowired
    private MonthlySettlementRepository fechamentoRepository;
    @Autowired
    private OrganizationRepository organizacaoRepository;
    @Autowired
    private UserRepository usuarioRepository;
    @Autowired
    private SupplierService produtorService;
    @Autowired
    private CollectionEntryService coletaService;
    @Autowired
    private MonthlySettlementService fechamentoService;

    private Organization orgA;
    private Organization orgB;
    private User userA;
    private User userB;
    private User adminGlobal;
    private Supplier produtorA;
    private Supplier produtorB;

    @BeforeEach
    void prepararCenario() {
        orgA = organizacaoRepository.save(new Organization("Cooperativa A"));
        orgB = organizacaoRepository.save(new Organization("Cooperativa B"));

        userA = usuario("a@teste.com", "Usuário A", orgA, Role.USER);
        userB = usuario("b@teste.com", "Usuário B", orgB, Role.USER);
        adminGlobal = usuario("admin@teste.com", "Admin", orgA, Role.ADMIN);

        produtorA = produtor("Supplier Só A", orgA);
        produtorB = produtor("Supplier Só B", orgB);
    }

    private User usuario(String email, String nome, Organization org, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setNome(nome);
        u.setSenha("{noop}senha");
        u.setRole(role);
        u.setOrganization(org);
        return usuarioRepository.save(u);
    }

    private Supplier produtor(String nome, Organization org) {
        Supplier p = new Supplier();
        p.setNome(nome);
        p.setAtivo(true);
        p.setOrganization(org);
        return produtorRepository.save(p);
    }

    private void autenticar(User u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("listarSupplieres devolve só os da própria organização")
    void listarSupplieresRespeitaEscopo() {
        autenticar(userA);
        var pagina = produtorService.listarTodos(
                org.springframework.data.domain.Pageable.unpaged());

        assertEquals(List.of(produtorA.getId()), pagina.getContent().stream().map(p -> p.id()).toList(),
                "Usuário A deve ver apenas o produtor da própria organização.");
    }

    @Test
    @DisplayName("buscar produtor de outra organização responde 404, não 403 nem o dado")
    void buscarSupplierAlheioDa404() {
        autenticar(userA);

        // O dado existe, mas para outra organização: 404 é o que esconde a
        // existência. Um 403 confirmaria que aquele produtor existe.
        assertThrows(EntityNotFoundException.class, () -> produtorService.buscarPorId(produtorB.getId()));
    }

    @Test
    @DisplayName("ADMIN enxerga as duas organizações")
    void adminEnxergaTodas() {
        autenticar(adminGlobal);
        var pagina = produtorService.listarTodos(
                org.springframework.data.domain.Pageable.unpaged());

        assertEquals(2, pagina.getTotalElements(),
                "ADMIN tem escopo global e deve enxergar todas as organizações.");
    }

    @Test
    @DisplayName("criar produtor o atribui à organização de quem criou")
    void criarSupplierHerdarOrganization() {
        autenticar(userB);
        var dto = new com.erp.ocr.dto.SupplierRequestDto(
                "Novo Supplier B", "Linha 1", "pix-novo", true, false, null);

        var criado = produtorService.criar(dto);

        var persistido = produtorRepository.findById(criado.id()).orElseThrow();
        assertEquals(orgB.getId(), persistido.getOrganization().getId(),
                "Supplier criado por usuário da B deve pertencer à B.");
    }

    @Test
    @DisplayName("criar coleta para produtor alheio é rejeitado")
    void criarColetaParaSupplierAlheio() {
        autenticar(userA);
        var dto = new CollectionEntryDto(java.time.LocalDate.of(2026, 3, 10), 50, produtorB.getId());

        assertThrows(EntityNotFoundException.class, () -> coletaService.criar(dto));
        assertTrue(coletaRepository.findAll().stream()
                        .noneMatch(c -> c.getSupplier().getId().equals(produtorB.getId())),
                "Nenhuma coleta pode ser criada em nome de produtor de outra organização.");
    }

    @Test
    @DisplayName("coletas de produtor alheio não aparecem na listagem do período")
    void coletasAlheiasNaoAparecem() {
        autenticar(userA);
        // Coleta do produtor B, dentro do mesmo período pedido por A.
        var doB = new com.erp.ocr.model.CollectionEntry(
                null, java.time.LocalDate.of(2026, 3, 10), 999, produtorB);
        coletaRepository.save(doB);

        var paginaA = coletaService.listarComFiltros(produtorA.getId(),
                java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 31),
                org.springframework.data.domain.Pageable.unpaged());

        assertTrue(paginaA.getContent().stream().noneMatch(c -> c.quantidadeLitros() == 999),
                "A listagem por período não pode incluir coleta de outra organização.");
    }

    @Test
    @DisplayName("fechamento de outra organização não aparece nos relatórios")
    void fechamentoAlheioNaoAparece() {
        autenticar(userA);

        var paginaA = fechamentoService.listarComFiltros(null, null, null,
                org.springframework.data.domain.Pageable.unpaged());

        assertTrue(paginaA.getContent().stream().noneMatch(f -> f.produtorId().equals(produtorB.getId())),
                "Relatórios não podem incluir fechamento de outra organização.");
    }

    @Test
    @DisplayName("produtores sem fechamento não vazam entre organizações")
    void semFechamentoRespeitaEscopo() {
        autenticar(userA);
        // Ambos têm coleta e nenhum tem fechamento: A deve ver só o seu.
        coletaRepository.save(new com.erp.ocr.model.CollectionEntry(
                null, java.time.LocalDate.of(2026, 3, 10), 100, produtorA));
        coletaRepository.save(new com.erp.ocr.model.CollectionEntry(
                null, java.time.LocalDate.of(2026, 3, 10), 100, produtorB));

        var visiveis = produtorService.listarSemFechamento(
                java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 31));

        assertEquals(List.of(produtorA.getId()), visiveis.stream().map(p -> p.id()).toList(),
                "A lista de 'quem falta fechar' não pode incluir produtor de outra organização.");
    }

    @Test
    @DisplayName("deletar fechamento de outra organização responde 404 e não apaga o registro")
    void deletarFechamentoAlheioDa404() {
        var doB = fechamento(produtorB);
        autenticar(userA);

        // O id existe, mas é de outra organização: 404 esconde a existência, e
        // o registro precisa continuar no banco.
        assertThrows(EntityNotFoundException.class, () -> fechamentoService.deletar(doB.getId()));
        assertTrue(fechamentoRepository.findById(doB.getId()).isPresent(),
                "Fechamento de outra organização não pode ser apagado.");
    }

    @Test
    @DisplayName("ADMIN, sem escopo, apaga fechamento de qualquer organização")
    void adminApagaFechamentoDeOutraOrganization() {
        var doB = fechamento(produtorB);
        autenticar(adminGlobal);

        fechamentoService.deletar(doB.getId());

        assertTrue(fechamentoRepository.findById(doB.getId()).isEmpty(),
                "ADMIN tem escopo global e deve conseguir apagar.");
    }

    private com.erp.ocr.model.MonthlySettlement fechamento(Supplier p) {
        var f = new com.erp.ocr.model.MonthlySettlement();
        f.setSupplier(p);
        f.setMes(3);
        f.setAno(2026);
        f.setDataInicio(java.time.LocalDate.of(2026, 3, 1));
        f.setDataFim(java.time.LocalDate.of(2026, 3, 31));
        f.setTotalLitros(100);
        f.setPrecoLitro(new java.math.BigDecimal("2.00"));
        f.setTotalBruto(new java.math.BigDecimal("200.00"));
        f.setDeductions(java.math.BigDecimal.ZERO);
        f.setTotalLiquido(new java.math.BigDecimal("200.00"));
        f.setStatusPagamento("Pendente");
        return fechamentoRepository.save(f);
    }
}
