package com.erp.ocr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.exception.OcrQuotaExceededException;
import com.erp.ocr.model.OcrUsage;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OcrUsageRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Cota diária de uploads de OCR por usuário (B5).
 * <p>
 * O contador vive em {@code ocr_usage} (uma linha por usuário/dia), e não em
 * memória: reiniciar a aplicação não devolve cota, e a decisão é a mesma em
 * qualquer instância. A leitura para consumo usa lock pessimista para que dois
 * uploads simultâneos do mesmo usuário não leiam o mesmo valor e estourem o
 * limite.
 * <p>
 * {@code OCR_DAILY_QUOTA <= 0} desabilita a cota (comportamento de rollback
 * operacional, sem precisar de deploy de código). O dia é sempre o dia civil
 * no fuso {@code OCR_QUOTA_ZONE}, não UTC: a virada precisa acontecer à
 * meia-noite de Brasília.
 */
@Service
public class OcrQuotaService {

    private static final Logger logger = LoggerFactory.getLogger(OcrQuotaService.class);

    private final OcrUsageRepository repository;
    private final OrganizationContext organizacaoContexto;
    private final Clock clock;
    private final TransactionTemplate transacaoDoInsert;
    private final int limiteDiario;
    private final ZoneId zona;

    @Autowired
    public OcrQuotaService(OcrUsageRepository repository,
            OrganizationContext organizacaoContexto,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${ocr.quota.daily:50}") int limiteDiario,
            @Value("${ocr.quota.zone:America/Sao_Paulo}") String zona) {
        this.repository = repository;
        this.organizacaoContexto = organizacaoContexto;
        this.clock = clock;
        // O INSERT da primeira utilização do dia precisa de transação própria:
        // uma violação de unicidade dentro da transação externa a marcaria como
        // rollback-only, e o catch/releitura terminaria em
        // UnexpectedRollbackException no commit (500). Com REQUIRES_NEW, só a
        // tentativa de INSERT sofre rollback; a transação que faz o
        // lock/incremento continua válida para reler a linha vencedora.
        this.transacaoDoInsert = new TransactionTemplate(transactionManager);
        this.transacaoDoInsert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.limiteDiario = limiteDiario;
        this.zona = ZoneId.of(zona);
    }

    /**
     * Consome uma unidade da cota do usuário autenticado.
     *
     * @return quantos uploads já foram usados hoje, o limite e o instante de reset
     * @throws OcrQuotaExceededException quando o limite do dia já foi atingido
     * @throws IllegalStateException     se não houver usuário autenticado (rota
     *                                   protegida não deveria chegar aqui)
     */
    @Transactional
    public OcrQuotaConsumption consumir() {
        User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario == null || usuario.getId() == null) {
            throw new IllegalStateException(
                    "Não há usuário autenticado para contabilizar a cota de OCR.");
        }

        LocalDate dia = LocalDate.now(clock.withZone(zona));
        Instant reset = resetDoDia(dia);

        // Cota desabilitada: não cria linha no banco nem bloqueia o upload.
        if (limiteDiario <= 0) {
            return new OcrQuotaConsumption(0, 0, reset);
        }

        OcrUsage uso = buscarComLockOuCriar(usuario.getId(), dia);

        if (uso.getContagem() >= limiteDiario) {
            throw new OcrQuotaExceededException(limiteDiario, reset, segundosAte(reset));
        }

        uso.setContagem(uso.getContagem() + 1);
        uso.setAtualizadoEm(LocalDateTime.now(clock.withZone(zona)));
        repository.save(uso);

        return new OcrQuotaConsumption(uso.getContagem(), limiteDiario, reset);
    }

    /**
     * Devolve uma unidade consumida quando a chamada ao OCR falha antes de
     * produzir resultado. Sem isso, um erro do provedor (429/503) queimava a
     * cota diária do usuário sem que ele tivesse extraído nada.
     * <p>
     * É idempotente na prática: nunca deixa a contagem abaixo de zero e não
     * cria linha para quem não consumiu.
     */
    @Transactional
    public void devolver() {
        User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario == null || usuario.getId() == null || limiteDiario <= 0) {
            return;
        }

        LocalDate dia = LocalDate.now(clock.withZone(zona));
        Optional<OcrUsage> uso = repository.findComLockByUserIdAndDia(usuario.getId(), dia);
        if (uso.isEmpty()) {
            return;
        }

        OcrUsage atual = uso.get();
        if (atual.getContagem() <= 0) {
            return;
        }

        atual.setContagem(atual.getContagem() - 1);
        atual.setAtualizadoEm(LocalDateTime.now(clock.withZone(zona)));
        repository.save(atual);
        logger.debug("Cota de OCR devolvida para usuario {} em {}: {} restante(s)",
                usuario.getId(), dia, limiteDiario - atual.getContagem());
    }

    /**
     * Leitura sem consumir, para exibir a cota restante sem gastá-la. Não
     * bloqueia nem cria registro: um usuário que nunca fez OCR aparece com
     * {@code usados = 0}.
     */
    @Transactional(readOnly = true)
    public OcrQuotaConsumption consultarSemConsumir() {
        User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario == null || usuario.getId() == null) {
            throw new IllegalStateException(
                    "Não há usuário autenticado para consultar a cota de OCR.");
        }

        LocalDate dia = LocalDate.now(clock.withZone(zona));
        Instant reset = resetDoDia(dia);

        if (limiteDiario <= 0) {
            return new OcrQuotaConsumption(0, 0, reset);
        }

        int usados = repository.findByUserIdAndDia(usuario.getId(), dia)
                .map(OcrUsage::getContagem)
                .orElse(0);
        return new OcrQuotaConsumption(usados, limiteDiario, reset);
    }

    /**
     * Busca a linha do dia com lock. Na primeira utilização ela não existe e
     * é criada com contagem 0. Duas requisições simultâneas do mesmo usuário
     * podem tentar inserir a mesma PK: uma vence, a outra recebe violação de
     * unicidade/conflito de serialização e relê a linha vencedora com lock.
     * <p>
     * O INSERT roda em {@code REQUIRES_NEW}: se falhar, faz rollback isolado e
     * não contamina a transação externa (que segue válida para a releitura).
     */
    private OcrUsage buscarComLockOuCriar(Long usuarioId, LocalDate dia) {
        Optional<OcrUsage> existente = repository.findComLockByUserIdAndDia(usuarioId, dia);
        if (existente.isPresent()) {
            return existente.get();
        }

        OcrUsage novo = new OcrUsage();
        novo.setUserId(usuarioId);
        novo.setDia(dia);
        novo.setContagem(0);
        novo.setAtualizadoEm(LocalDateTime.now(clock.withZone(zona)));

        RuntimeException conflito = null;
        try {
            transacaoDoInsert.execute(status -> repository.saveAndFlush(novo));
        } catch (DataIntegrityViolationException | ConcurrencyFailureException ex) {
            // Esperado na corrida de primeira inserção: outra requisição criou
            // a mesma (usuario_id, dia) primeiro.
            conflito = ex;
            logger.debug("Corrida ao criar ocr_usage para usuario {} em {}; relendo com lock",
                    usuarioId, dia);
        }

        // Mesmo quando o INSERT venceu, a entidade dele veio da transação
        // interna (destacada, com contagem 0). Reler com lock na transação
        // externa é o que garante que o incremento aconteça sobre o valor
        // atual: sem isso, o vencedor e o perdedor da corrida poderiam ambos
        // incrementar de 0 para 1 e um sobrescrever o outro (lost update).
        final RuntimeException causa = conflito;
        try {
            Optional<OcrUsage> relido = repository.findComLockByUserIdAndDia(usuarioId, dia);
            if (relido.isPresent()) {
                return relido.get();
            }
            throw new ConcurrencyFailureException(
                    "Não foi possível registrar o consumo diário de OCR.", causa);
        } catch (ConcurrencyFailureException conflitoNaReleitura) {
            throw conflitoNaReleitura;
        } catch (RuntimeException outraFalha) {
            throw new ConcurrencyFailureException(
                    "Não foi possível registrar o consumo diário de OCR.", outraFalha);
        }
    }

    private Instant resetDoDia(LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(zona).toInstant();
    }

    private long segundosAte(Instant instante) {
        long segundos = Duration.between(clock.instant(), instante).getSeconds();
        return Math.max(1L, segundos);
    }
}
