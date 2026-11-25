package com.erp.ocr.isolation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.Deduction;
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.DeductionRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava a garantia de que <strong>não existe caminho de leitura de desconto
 * que escape do escopo de organização</strong>.
 * <p>
 * Um desconto herda o escopo do fechamento a que pertence. Isso é seguro
 * enquanto a única forma de chegar a ele for carregar o fechamento por um
 * finder escopado. O risco real não era um bug no código existente, e sim um
 * <em>finder morto sem escopo</em> esperando alguém usá-lo — sobreviveu a
 * três ondas de correção de tenancy justamente por nunca ser exercitado.
 */
@SpringBootTest
@Transactional
@DisplayName("Deduction: nenhum caminho de leitura escapa da organização")
class DeductionIsolationTest {

    @MockitoBean
    private GeminiOcrService geminiOcrService;

    @MockitoBean
    private AzureOcrService azureOcrService;

    @Autowired
    private DeductionRepository descontoRepository;
    @Autowired
    private MonthlySettlementRepository fechamentoRepository;
    @Autowired
    private SupplierRepository produtorRepository;
    @Autowired
    private CollectionEntryRepository coletaRepository;
    @Autowired
    private OrganizationRepository organizacaoRepository;
    @Autowired
    private UserRepository usuarioRepository;

    private Organization orgA;
    private Organization orgB;
    private User userA;
    private MonthlySettlement fechamentoB;

    @BeforeEach
    void prepararCenario() {
        SecurityContextHolder.clearContext();
        orgA = organizacaoRepository.save(new Organization("Cooperativa A do Teste"));
        orgB = organizacaoRepository.save(new Organization("Cooperativa B do Teste"));
        userA = usuario("a@teste.com", "Usuário A", orgA, Role.USER);

        Supplier produtorB = produtor("Supplier Só B", orgB);
        CollectionEntry coleta = new CollectionEntry();
        coleta.setData(LocalDate.of(2026, 3, 10));
        coleta.setQuantidadeLitros(120);
        coleta.setSupplier(produtorB);
        coletaRepository.save(coleta);

        fechamentoB = new MonthlySettlement();
        fechamentoB.setSupplier(produtorB);
        fechamentoB.setDataInicio(LocalDate.of(2026, 2, 21));
        fechamentoB.setDataFim(LocalDate.of(2026, 3, 20));
        fechamentoB.setMes(3);
        fechamentoB.setAno(2026);
        fechamentoB.setTotalLitros(120);
        fechamentoB.setPrecoLitro(new BigDecimal("2.5000"));
        fechamentoB.setPrecoFinal(new BigDecimal("2.5000"));
        fechamentoB.setTotalBruto(new BigDecimal("300.00"));
        fechamentoB.setDeductions(new BigDecimal("30.00"));
        fechamentoB.setTotalLiquido(new BigDecimal("270.00"));
        fechamentoB.setStatusPagamento("Pendente");
        fechamentoRepository.save(fechamentoB);

        Deduction desconto = new Deduction();
        desconto.setDescricao("Empréstimo de equipamento");
        desconto.setValor(new BigDecimal("30.00"));
        desconto.setSettlement(fechamentoB);
        fechamentoB.getListaDeductions().add(desconto);
        fechamentoRepository.save(fechamentoB);
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
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    @DisplayName("o repositório de desconto não expõe nenhum finder sem escopo")
    void nenhumFinderSemEscopo() {
        List<Method> metodos = Arrays.stream(DeductionRepository.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .toList();

        assertThat(metodos)
                .as("todo finder de Deduction teria que receber organizacaoId; um finder que "
                        + "sórecebe o id do fechamento permitiria ler o desconto de outra organização")
                .isEmpty();
    }

    @Test
    @DisplayName("USER da organização A não alcança o fechamento da organização B, e nem seus descontos")
    void userNaoAlcancaDeductionAlheio() {
        autenticar(userA);

        // O caminho oficial: buscar o fechamento com escopo.
        assertThat(fechamentoRepository.findVisivelById(fechamentoB.getId(), orgA.getId()))
                .as("fechamento de outra organização não pode ser encontrado")
                .isEmpty();

        // E a consequência: não há caminho para os descontos dele.
        assertThat(fechamentoRepository.findVisivelById(fechamentoB.getId(), orgA.getId())
                .flatMap(f -> f.getListaDeductions().stream().findAny()))
                .isEmpty();
    }

    @Test
    @DisplayName("ADMIN, sem escopo, alcança o fechamento e os descontos dele")
    void adminAlcancaDeduction() {
        User admin = usuario("admin@teste.com", "Admin", orgA, Role.ADMIN);
        autenticar(admin);

        MonthlySettlement encontrado = fechamentoRepository
                .findVisivelById(fechamentoB.getId(), null)
                .orElseThrow();
        assertThat(encontrado.getListaDeductions())
                .as("ADMIN enxerga todas as organizações, então os descontos também")
                .hasSize(1);
        assertThat(encontrado.getDeductions())
                .isEqualByComparingTo(encontrado.getListaDeductions().get(0).getValor());
    }

    @Test
    @DisplayName("o desconto continua acessível a partir do fechamento da própria organização")
    void descontoDaPropriaOrganizationContinuaAcessivel() {
        // O repository sem finder não pode atrapalhar o caminho normal: a
        // associação continua carregando os descontos.
        MonthlySettlement encontrado = fechamentoRepository
                .findVisivelById(fechamentoB.getId(), orgB.getId())
                .orElseThrow();
        assertThat(encontrado.getListaDeductions()).hasSize(1);
        assertThat(encontrado.getDeductions()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("remover o finder não quebrou a gravação em cascata do desconto")
    void gravacaoEmCascataContinuaFuncionando() {
        // O desconto foi criado em cascata a partir do fechamento; o finder
        // removido era só de LEITURA. Confirma que a escrita continua inteira.
        assertThat(descontoRepository.count()).isEqualTo(1);
        Deduction salvo = descontoRepository.findAll().get(0);
        assertThat(salvo.getSettlement()).isNotNull();
        assertThat(salvo.getSettlement().getId()).isEqualTo(fechamentoB.getId());
    }
}
