package com.erp.ocr.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.erp.ocr.exception.OcrQuotaExceededException;
import com.erp.ocr.model.OcrUsage;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OcrUsageRepository;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.service.ocr.GeminiOcrService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prova que a corrida de primeira inserção e os incrementos concorrentes da
 * cota de OCR não produzem 500/`UnexpectedRollbackException` nem estouram o
 * limite.
 * <p>
 * Usa o H2/contexto real de propósito: o bug corrigido só aparece com
 * transações de verdade (a tentativa de INSERT precisa falhar e sofrer
 * rollback isolado por {@code REQUIRES_NEW}). Com repositório mockado, o
 * problema de rollback-only não existiria.
 */
@SpringBootTest(properties = {
        "ocr.quota.daily=40",
        // Folga no lock do H2: duas threads disputam a mesma linha e a janela
        // padrão de 1s deixaria o teste instável sem provar nada sobre a cota.
        "spring.datasource.url=jdbc:h2:mem:ocrquota;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@DisplayName("Concorrência da cota diária de OCR")
class OcrQuotaConcurrencyTest {

    private static final int LIMITE = 40;
    private static final int TENTATIVAS_POR_THREAD = 30;
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    // Os providers de OCR exigem credenciais que não existem no perfil de
    // teste; não participam deste cenário (a cota é exercitada antes do OCR).
    @MockitoBean
    private GeminiOcrService geminiOcrService;

    @MockitoBean
    private com.erp.ocr.service.AzureOcrService azureOcrService;

    @Autowired
    private OcrQuotaService ocrQuotaService;

    @Autowired
    private OcrUsageRepository ocrUsageRepository;

    @Autowired
    private UserRepository usuarioRepository;

    @Autowired
    private OrganizationRepository organizacaoRepository;

    private User usuario;

    @BeforeEach
    void setUp() {
        ocrUsageRepository.deleteAll();

        Organization organizacao = organizacaoRepository
                .save(new Organization("Org concorrência " + UUID.randomUUID()));

        User novo = new User();
        novo.setEmail("concorrencia-" + UUID.randomUUID() + "@erpocr.test");
        novo.setNome("Usuário concorrência");
        novo.setSenha("senha-de-teste");
        novo.setRole(Role.USER);
        novo.setOrganization(organizacao);
        usuario = usuarioRepository.save(novo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ocrUsageRepository.deleteAll();
    }

    @Test
    @DisplayName("duas threads não passam do limite, não dão 500 e a contagem final está correta")
    void duasThreadsRespeitamOLimite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch pronto = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger sucessos = new AtomicInteger();
        List<Throwable> inesperados = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities()));
                    try {
                        pronto.countDown();
                        largada.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < TENTATIVAS_POR_THREAD; i++) {
                            try {
                                ocrQuotaService.consumir();
                                sucessos.incrementAndGet();
                            } catch (OcrQuotaExceededException esperado) {
                                // Limite atingido: é o comportamento correto.
                            } catch (Throwable inesperado) {
                                inesperados.add(inesperado);
                            }
                        }
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                    return null;
                }));
            }

            assertTrue(pronto.await(10, TimeUnit.SECONDS), "threads não iniciaram");
            largada.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(inesperados.isEmpty(),
                () -> "nenhuma exceção inesperada (500/rollback-only) era permitida, mas veio: " + inesperados);
        LocalDate hoje = LocalDate.now(ZONA);
        OcrUsage usoFinal = ocrUsageRepository.findByUserIdAndDia(usuario.getId(), hoje)
                .orElseThrow(() -> new AssertionError("a linha de ocr_usage do dia deveria existir"));
        assertEquals(LIMITE, usoFinal.getContagem(),
                "a contagem persistida precisa refletir exatamente os consumos aceitos; sucessos="
                        + sucessos.get());
        assertEquals(LIMITE, sucessos.get(),
                "o total de consumos bem-sucedidos deve ser exatamente o limite; contagem persistida="
                        + usoFinal.getContagem());
    }
}
