package com.erp.ocr.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.dto.CollectionEntryDto;
import com.erp.ocr.dto.CollectionEntryResponseDto;
import com.erp.ocr.dto.CollectionBatchItemDto;
import com.erp.ocr.model.User;
import com.erp.ocr.service.ApiRateLimiterService;
import com.erp.ocr.service.CollectionEntryService;
import com.erp.ocr.service.OcrQuotaConsumption;
import com.erp.ocr.service.OcrQuotaService;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/collections")
public class CollectionEntryController {

    // Campos aceitos em ?sortBy=. Um campo desconhecido produz 400 em vez de
    // 500, e o cliente não ordena por colunas fora do contrato.
    private static final java.util.Set<String> SORT_FIELDS = java.util.Set.of("data", "quantidadeLitros", "id");

    @Autowired
    private CollectionEntryService coletaService;

    @Autowired
    private ApiRateLimiterService apiRateLimiterService;

    @Autowired
    private OcrQuotaService ocrQuotaService;

    @Autowired
    private OrganizationContext organizacaoContexto;

    // Tetos por usuário. Ambos ficam em application.properties com default,
    // para ajuste operacional sem deploy de código.
    @Value("${api.rate.ocr.limit:12}")
    private int ocrRateLimit;

    @Value("${api.rate.ocr.window-minutes:60}")
    private long ocrRateWindowMinutes;

    @Value("${api.rate.lote.limit:120}")
    private int loteRateLimit;

    @Value("${api.rate.lote.window-minutes:60}")
    private long loteRateWindowMinutes;

    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<CollectionEntryResponseDto>> listarTodas(
            @RequestParam(required = false) Long produtorId,
            @RequestParam(required = false) java.time.LocalDate inicio,
            @RequestParam(required = false) java.time.LocalDate fim,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "data") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        com.erp.ocr.util.PaginationUtil.validateSortField(sortBy, SORT_FIELDS);
        org.springframework.data.domain.Sort sort = sortDir.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.by(sortBy).ascending()
                : org.springframework.data.domain.Sort.by(sortBy).descending();
        org.springframework.data.domain.Pageable pageable = com.erp.ocr.util.PaginationUtil.of(page,
                size, sort);
        org.springframework.data.domain.Page<CollectionEntryResponseDto> coletas = coletaService
                .listarComFiltros(produtorId, inicio, fim, pageable);
        return ResponseEntity.ok(coletas);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CollectionEntryResponseDto> buscarPorId(@PathVariable Long id) {
        CollectionEntryResponseDto coleta = coletaService.buscarPorId(id);
        return ResponseEntity.ok(coleta);
    }

    @PostMapping
    public ResponseEntity<CollectionEntryResponseDto> criar(@RequestBody @Valid CollectionEntryDto dto) {
        CollectionEntryResponseDto novaColeta = coletaService.criar(dto);
        return ResponseEntity.status(201).body(novaColeta);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CollectionEntryResponseDto> atualizar(@PathVariable Long id,
            @RequestBody @Valid CollectionEntryDto dto) {
        CollectionEntryResponseDto coletaAtualizada = coletaService.atualizar(id, dto);
        return ResponseEntity.ok(coletaAtualizada);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        coletaService.deletar(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/supplier/{produtorId}/month/{ano}/{mes}")
    public ResponseEntity<List<CollectionEntryResponseDto>> buscarColetasPorSupplierEMes(
            @PathVariable Long produtorId,
            @PathVariable int ano,
            @PathVariable int mes) {
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("O mês deve estar entre 1 e 12.");
        }
        if (ano < 2000 || ano > 2100) {
            throw new IllegalArgumentException("O ano informado é inválido.");
        }
        List<CollectionEntryResponseDto> coletas = coletaService.buscarPorSupplierEMes(produtorId, ano, mes);
        return ResponseEntity.ok(coletas);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<CollectionEntryResponseDto>> salvarColetasEmLote(
            // Sem teto, um cliente autenticado podia enviar centenas de milhares
            // de registros: tudo era parseado em memória, deduplicado e gravado
            // numa transação que ainda é @Retryable (o trabalho pesado podia se
            // repetir 5 vezes). A tela de importação envia lotes mensais de
            // dezenas de linhas, então 1000 é folgado.
            @RequestBody @Valid @jakarta.validation.constraints.Size(max = 1000, message = "Um lote não pode exceder 1000 coletas; divida o envio.")
            List<CollectionBatchItemDto> coletasDto,
            HttpServletRequest request) {
        apiRateLimiterService.consumir("lote", chaveDoUser(request), loteRateLimit,
                Duration.ofMinutes(loteRateWindowMinutes));
        List<CollectionEntryResponseDto> coletasSalvas = coletaService.salvarColetasEmLote(coletasDto);
        return ResponseEntity.status(201).body(coletasSalvas);
    }

    @Autowired
    private com.erp.ocr.service.ocr.OcrServiceFactory ocrServiceFactory;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CollectionEntryController.class);

    @PostMapping(value = "/upload-batch", consumes = "multipart/form-data")
    public ResponseEntity<List<com.erp.ocr.dto.CollectionExtractedDto>> uploadBatchColetas(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "ocrService", required = false) String ocrService,
            // B17: explicit ano/mes instead of the OCR service always assuming "last
            // month" - required to correctly import an old sheet. Optional for backward
            // compatibility; omitting both keeps the previous "last month" default.
            @RequestParam(value = "year", required = false) Integer ano,
            @RequestParam(value = "month", required = false) Integer mes,
            HttpServletRequest request) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo foi enviado.");
        }
        if ((ano == null) != (mes == null)) {
            throw new IllegalArgumentException("Informe 'ano' e 'mes' juntos, ou nenhum dos dois.");
        }
        if (mes != null && (mes < 1 || mes > 12)) {
            throw new IllegalArgumentException("O mês deve estar entre 1 e 12.");
        }
        if (ano != null && (ano < 2000 || ano > 2100)) {
            throw new IllegalArgumentException("O ano informado é inválido.");
        }
        validarArquivoOCR(file);

        // B5: os dois limites vêm ANTES de qualquer chamada paga ao OCR. O
        // rate limit protege a taxa (janela fixa); a cota protege o total do
        // dia. Ordem: o mais barato primeiro, para não gastar cota de quem já
        // estourou a taxa.
        apiRateLimiterService.consumir("ocr", chaveDoUser(request), ocrRateLimit,
                Duration.ofMinutes(ocrRateWindowMinutes));
        OcrQuotaConsumption quota = ocrQuotaService.consumir();

        try {
            com.erp.ocr.service.ocr.OcrService service = ocrServiceFactory
                    .getOcrService(ocrService);
            logger.info("Processing OCR with service: {}", service.getServiceName());
            List<com.erp.ocr.dto.CollectionExtractedDto> coletas = service.extractBatchColetas(file, ano,
                    mes);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Ocr-Quota-Limit", Integer.toString(quota.limite()));
            headers.set("X-Ocr-Quota-Remaining", Integer.toString(quota.restantes()));
            headers.set("X-Ocr-Quota-Reset", quota.reset().toString());
            return ResponseEntity.ok().headers(headers).body(coletas);
        } catch (java.io.IOException | RuntimeException e) {
            // A cota é consumida antes da chamada paga. Se o provedor falhar
            // (credencial, 429, 5xx, arquivo inválido), o usuário não deve
            // pagar por uma extração que não aconteceu. Uma falha ao devolver
            // não pode mascarar o erro original.
            try {
                ocrQuotaService.devolver();
            } catch (RuntimeException falhaAoDevolver) {
                logger.warn("Não foi possível devolver a cota de OCR após falha", falhaAoDevolver);
                e.addSuppressed(falhaAoDevolver);
            }
            throw e;
        }
    }

    /**
     * Identidade usada nos contadores de rate limit: id do usuário autenticado
     * (estável mesmo se o e-mail mudar), e-mail como fallback e, por último, o
     * IP da conexão. Nunca o valor cru de X-Forwarded-For — o Tomcat só o
     * honra quando a conexão vem de um proxy interno
     * ({@code server.forward-headers-strategy=native}).
     */
    private String chaveDoUser(HttpServletRequest request) {
        User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario != null) {
            if (usuario.getId() != null) {
                return usuario.getId().toString();
            }
            if (usuario.getEmail() != null && !usuario.getEmail().isBlank()) {
                return usuario.getEmail();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Confere o conteúdo real do arquivo, e não o header {@code Content-Type}
     * nem a extensão do nome — ambos são controlados por quem envia e não
     * dizem nada sobre o conteúdo. Sem isso, o tipo do documento (e portanto o
     * caminho de processamento: PDF renderizado página a página, com uma
     * chamada paga de OCR por página) era decidido pelo atacante.
     */
    private void validarArquivoOCR(org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        byte[] head = new byte[8];
        int read;
        try (java.io.InputStream in = file.getInputStream()) {
            read = in.read(head);
        }
        if (read <= 0) {
            throw new IllegalArgumentException("O arquivo enviado está vazio.");
        }
        java.util.Arrays.fill(head, read, head.length, (byte) 0);

        boolean isPdf = head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-';
        boolean isImage = (head[0] & 0xFF) == 0x89 && head[1] == 'P' // PNG
                || (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 // JPEG
                || (head[0] & 0xFF) == 0x42 && head[1] == 'Z'; // BMP

        if (!isPdf && !isImage) {
            throw new IllegalArgumentException(
                    "Formato não suportado. Envie uma imagem (PNG, JPEG, BMP) ou um PDF.");
        }
    }

}