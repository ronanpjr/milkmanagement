package com.erp.ocr.service;

import com.azure.ai.documentintelligence.models.DocumentTable;
import com.azure.json.JsonProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import com.erp.ocr.dto.CollectionExtractedDto;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.SupplierRepository;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Cobertura de {@link AzureOcrService}.
 * <p>
 * O SDK recusa endpoint http quando a credencial é uma chave ("Key credentials
 * require HTTPS to prevent leaking the key"), e o cliente é montado dentro da
 * própria classe — não há como injetar um mock nem um SSLContext de teste. Para
 * cobrir a leitura da tabela (que é onde está a regra de negócio) sem rede e sem
 * depender de certificado local, o teste monta um {@link DocumentTable} em
 * memória pelo próprio desserializador do SDK e chama {@code parseTable}. Os
 * testes que passam pela API pública cobrem o tratamento de erro do
 * {@code extractBatchColetas}.
 * <p>
 * A chave usada é fake: a AZURE_DOCUMENT_INTELLIGENCE_KEY real nunca é lida.
 */
class AzureOcrServiceTest {

    private static final String CHAVE_FAKE = "chave-de-teste-fake-sem-valor-real";
    private static final byte[] PDF_FAKE = "%PDF-1.7\nfake-folha-de-coleta\n%%EOF"
            .getBytes(StandardCharsets.UTF_8);

    @Mock
    private SupplierRepository produtorRepository;

    @Mock
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    private AzureOcrService azureOcrService;

    /**
     * Supplier de teste montado por setters. O Supplier passou a exigir
     * organizacao (coluna NOT NULL) e nao tem mais construtor de todos os
     * argumentos; por posicao seria fragil e o tenant teria de entrar na
     * posicao certa sem o compilador ajudar.
     */
    private static Supplier produtorJoao() {
        Supplier p = new Supplier();
        p.setId(11L);
        p.setNome("João Silva");
        p.setLinha("Linha 1");
        p.setChavePix("pix");
        p.setFormaPagamento("Dinheiro");
        p.setAtivo(true);
        p.setUsaPagamentoPorQualidade(false);
        Organization org = new Organization("Cooperativa Teste");
        org.setId(1L);
        p.setOrganization(org);
        return p;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(produtorRepository.findVisiveisPorNome(anyString(), any()))
                .thenAnswer(invoc -> {
                    String nome = semAcento(invoc.<String>getArgument(0));
                    // findVisiveisPorNome devolve lista (o servico pega o
                    // primeiro), e o segundo argumento e a organizacao do
                    // usuario autenticado — any() para nao acoplar o teste ao
                    // id, que e o mesmo em todos os cenarios.
                    return nome.contains("joao silva")
                            ? List.of(produtorJoao())
                            : List.of();
                });

        // O servico filtra o produtor pela organizacao do usuario
        // autenticado; sem o mock, organizacaoId() devolveria null (padrao do
        // Mockito) e o filtro viraria "sem escopo".
        when(organizacaoContexto.organizacaoId()).thenReturn(1L);

        azureOcrService = new AzureOcrService("https://endpoint-de-teste.invalid", CHAVE_FAKE, produtorRepository,
                organizacaoContexto);
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /** Monta a tabela a partir das células "linha|coluna|conteúdo" e a faz passar por parseTable. */
    private List<CollectionExtractedDto> extrair(int ano, int mes, String... celulas) throws Exception {
        StringBuilder corpo = new StringBuilder();
        int linhas = 0;
        int colunas = 0;
        for (int i = 0; i < celulas.length; i++) {
            String[] partes = celulas[i].split("\\|", 3);
            int linha = Integer.parseInt(partes[0]);
            int coluna = Integer.parseInt(partes[1]);
            linhas = Math.max(linhas, linha + 1);
            colunas = Math.max(colunas, coluna + 1);
            if (i > 0) {
                corpo.append(',');
            }
            corpo.append("{\"rowIndex\":").append(linha)
                    .append(",\"columnIndex\":").append(coluna)
                    .append(",\"content\":\"").append(partes[2]).append("\"}");
        }
        DocumentTable tabela = DocumentTable.fromJson(JsonProviders.createReader(
                "{\"rowCount\":" + linhas + ",\"columnCount\":" + colunas + ",\"cells\":[" + corpo + "]}"));

        Method parseTable = AzureOcrService.class.getDeclaredMethod("parseTable", DocumentTable.class, List.class,
                int.class, int.class);
        parseTable.setAccessible(true);
        List<CollectionExtractedDto> coletas = new ArrayList<>();
        parseTable.invoke(azureOcrService, tabela, coletas, ano, mes);
        return coletas;
    }

    // ------------------------------------------------------------------
    // Cabeçalho e mapeamento de colunas
    // ------------------------------------------------------------------

    @Test
    void deveConverterCelulasDaTabelaEmLinhasDeColeta() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 01", "0|2|DIA 02",
                "1|0|1. João Silva", "1|1|150", "1|2|142",
                "2|0|2. Maria Souza", "2|1|98", "2|2|100");

        assertEquals(4, coletas.size());
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 1), 150),
                coletas.get(0));
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 2), 142),
                coletas.get(1));
        assertEquals(new CollectionExtractedDto("Maria Souza", null, false, LocalDate.of(2025, 3, 1), 98),
                coletas.get(2));
    }

    @Test
    void deveAceitarCabecalhoDeDiaComoNumeroPuro() throws Exception {
        // Layout sem "DIA": o cabeçalho é só o número, e número em coluna <= 1 é
        // ignorado para não confundir a coluna de produtor com um dia.
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|1", "0|1|Fornecedor", "0|2|01", "0|3|02",
                "1|1|João Silva", "1|2|150", "1|3|142");

        assertEquals(2, coletas.size());
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 1), 150),
                coletas.get(0));
        assertEquals(new CollectionExtractedDto("João Silva", 11L, true, LocalDate.of(2025, 3, 2), 142),
                coletas.get(1));
    }

    @Test
    void deveUsarAColunaIndicadaPorPalavraChaveDeSupplier() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|1|PRODUTOR", "0|2|DIA 05",
                "1|1|João Silva", "1|2|300");

        assertEquals(1, coletas.size());
        assertEquals(11L, coletas.get(0).produtorId());
        assertEquals(LocalDate.of(2025, 3, 5), coletas.get(0).data());
        assertEquals(300, coletas.get(0).quantidadeLitros());
    }

    @Test
    void deveUsarPrimeiraColunaComoFallbackDeSupplier() throws Exception {
        // Cabeçalho "LEITE" na coluna 0 não diz "fornecedor/produtor/nome", mas é a
        // primeira célula de coluna <= 1 que não é dia — então vira o fallback.
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|LEITE", "0|1|DIA 01",
                "1|0|João Silva", "1|1|88");

        assertEquals(1, coletas.size());
        assertEquals(11L, coletas.get(0).produtorId());
    }

    @Test
    void deveUsarColunaZeroQuandoNaoHaNenhumCabecalhoDeSupplier() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|", "0|1|DIA 01",
                "1|0|João Silva", "1|1|77");

        assertEquals(1, coletas.size());
        assertEquals(77, coletas.get(0).quantidadeLitros());
    }

    @Test
    void deveLimparNumeroECaracteresDoNomeLidoPelaOcr() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 03",
                "1|0|12 - João Silva", "1|1|64");

        assertEquals(1, coletas.size());
        assertTrue(coletas.get(0).matched());
        // O nome devolvido é o do cadastro, não o texto sujo da OCR.
        assertEquals("João Silva", coletas.get(0).produtorNomeDetectado());
    }

    // ------------------------------------------------------------------
    // Linhas que não entram
    // ------------------------------------------------------------------

    @Test
    void deveIgnorarLinhasDeTotalEMedia() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 01",
                "1|0|João Silva", "1|1|120",
                "2|0|TOTAL", "2|1|9999",
                "3|0|MÉDIA", "3|1|456");

        assertEquals(1, coletas.size());
        assertEquals(120, coletas.get(0).quantidadeLitros());
    }

    @Test
    void deveIgnorarCelulaDeVolumeQueNaoEInteiro() throws Exception {
        // Regra do usuário: sem traço, sem vírgula, sem ponto — só inteiros.
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 01", "0|2|DIA 02", "0|3|DIA 03", "0|4|DIA 04",
                "1|0|João Silva", "1|1|120", "1|2|-", "1|3|1.200", "1|4|0");

        assertEquals(1, coletas.size());
        assertEquals(120, coletas.get(0).quantidadeLitros());
    }

    @Test
    void deveIgnorarLinhaSemNomeDeSupplier() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 01",
                "1|0|1", "1|1|120",
                "2|0|João Silva", "2|1|130");

        assertEquals(1, coletas.size());
        assertEquals(130, coletas.get(0).quantidadeLitros());
    }

    @Test
    void deveDescartarDiaQueNaoExisteNoMes() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 4,
                "0|0|Fornecedor", "0|1|DIA 31",
                "1|0|João Silva", "1|1|120");

        assertTrue(coletas.isEmpty());
    }

    @Test
    void deveTrazerLinhaSemSupplierCadastradoComoNaoConciliada() throws Exception {
        List<CollectionExtractedDto> coletas = extrair(2025, 3,
                "0|0|Fornecedor", "0|1|DIA 09",
                "1|0|Maria Souza", "1|1|77");

        assertEquals(1, coletas.size());
        assertFalse(coletas.get(0).matched());
        assertNull(coletas.get(0).produtorId());
        assertEquals("Maria Souza", coletas.get(0).produtorNomeDetectado());
        assertEquals(77, coletas.get(0).quantidadeLitros());
    }

    // ------------------------------------------------------------------
    // API pública: tratamento de erro do extractBatchColetas
    // ------------------------------------------------------------------

    @Test
    void extractBatchColetas_DeveFalharComMensagemPropriaQuandoOEndpointNaoResponde() throws Exception {
        int portaLivre;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            portaLivre = socket.getLocalPort();
        }
        AzureOcrService semBackend = new AzureOcrService("https://127.0.0.1:" + portaLivre, CHAVE_FAKE,
                produtorRepository, organizacaoContexto);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "folha.pdf", "application/pdf", PDF_FAKE);

        RuntimeException erro = assertThrows(RuntimeException.class,
                () -> semBackend.extractBatchColetas(arquivo, 2025, 3));

        assertTrue(erro.getMessage().startsWith("Failed to process document"), erro.getMessage());
    }

    @Test
    void extractBatchColetas_DeveRecusarEnviarAChaveEmHttpPuro() {
        AzureOcrService emHttpPuro = new AzureOcrService("http://127.0.0.1:9", CHAVE_FAKE, produtorRepository,
                organizacaoContexto);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "folha.pdf", "application/pdf", PDF_FAKE);

        // Falha barulhenta e antes de qualquer requisição: melhor do que enviar a
        // chave da Azure em texto puro.
        RuntimeException erro = assertThrows(RuntimeException.class,
                () -> emHttpPuro.extractBatchColetas(arquivo, 2025, 3));

        assertTrue(erro.getMessage().contains("Failed to process document"), erro.getMessage());
    }

    @Test
    void getServiceName_DeveIdentificarOProvedor() {
        assertEquals("Azure Document Intelligence", azureOcrService.getServiceName());
    }
}
