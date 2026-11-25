package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.exception.OcrQuotaExceededException;
import com.erp.ocr.model.OcrUsage;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OcrUsageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cota diária de OCR (B5). O {@link Clock} é injetado para que "o dia virou" e
 * o reset da meia-noite sejam determinísticos, sem espera real.
 */
class OcrQuotaServiceTest {

    private static final Long USUARIO_ID = 42L;
    private static final String ZONA = "America/Sao_Paulo";
    // 15:30 UTC = 12:30 em São Paulo (UTC-3).
    private static final Instant AGORA = Instant.parse("2026-10-20T15:30:00Z");
    private static final LocalDate DIA = LocalDate.of(2026, 10, 20);
    private static final Instant RESET = Instant.parse("2026-10-21T03:00:00Z");

    @Mock
    private OcrUsageRepository repository;

    @Mock
    private OrganizationContext organizacaoContexto;

    private PlatformTransactionManager transactionManager;
    private User usuario;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        usuario = new User();
        usuario.setId(USUARIO_ID);
        usuario.setEmail("produtor@erpocr.test");
        when(organizacaoContexto.usuarioAutenticado()).thenReturn(usuario);
    }

    private OcrQuotaService service(int limite, Instant instante) {
        Clock clock = Clock.fixed(instante, ZoneOffset.UTC);
        return new OcrQuotaService(repository, organizacaoContexto, clock, transactionManager, limite, ZONA);
    }

    private OcrUsage uso(int contagem) {
        OcrUsage uso = new OcrUsage();
        uso.setUserId(USUARIO_ID);
        uso.setDia(DIA);
        uso.setContagem(contagem);
        uso.setAtualizadoEm(java.time.LocalDateTime.of(2026, 10, 20, 12, 0));
        return uso;
    }

    @Test
    @DisplayName("consome até o limite e incrementa o contador do dia")
    void consomeAteOLimite() {
        OcrUsage uso = uso(0);
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA)).thenReturn(Optional.of(uso));

        OcrQuotaService service = service(3, AGORA);

        OcrQuotaConsumption primeiro = service.consumir();
        OcrQuotaConsumption segundo = service.consumir();
        OcrQuotaConsumption terceiro = service.consumir();

        assertEquals(1, primeiro.usados());
        assertEquals(2, segundo.usados());
        assertEquals(3, terceiro.usados());
        assertEquals(2, primeiro.restantes());
        assertEquals(0, terceiro.restantes());
        assertEquals(RESET, terceiro.reset());
        verify(repository, times(3)).save(uso);
    }

    @Test
    @DisplayName("no limite, lança 429 com retry até a meia-noite do fuso configurado")
    void noLimiteLancaExcecaoComRetryAteAMeiaNoite() {
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA))
                .thenReturn(Optional.of(uso(3)));

        OcrQuotaExceededException ex = assertThrows(OcrQuotaExceededException.class,
                () -> service(3, AGORA).consumir());

        assertEquals(OcrQuotaExceededException.SCOPE, ex.getScope());
        assertEquals(3, ex.getLimit());
        assertEquals(0, ex.getRemaining());
        assertEquals(RESET, ex.getResetInstant());
        // De 12:30 (São Paulo) até 00:00 do dia seguinte: 11h30 = 41400s.
        assertEquals(41_400L, ex.getRetryAfterSeconds());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("em outro dia, cria a linha nova e a cota volta a valer")
    void emOutroDiaACotaReseta() {
        Instant amanha = Instant.parse("2026-10-21T15:30:00Z");
        LocalDate diaSeguinte = LocalDate.of(2026, 10, 21);
        OcrUsage recemLida = uso(0);
        recemLida.setDia(diaSeguinte);
        // Primeira leitura: linha ainda não existe. Depois do INSERT isolado,
        // o serviço relê com lock na transação externa antes de incrementar.
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, diaSeguinte))
                .thenReturn(Optional.empty(), Optional.of(recemLida));
        when(repository.saveAndFlush(any(OcrUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        OcrQuotaConsumption resultado = service(3, amanha).consumir();

        assertEquals(1, resultado.usados());
        assertEquals(3, resultado.limite());
        assertEquals(Instant.parse("2026-10-22T03:00:00Z"), resultado.reset());
        verify(repository).saveAndFlush(argThat(uso -> diaSeguinte.equals(uso.getDia())
                && USUARIO_ID.equals(uso.getUserId())));
        // O vencedor do INSERT também relê com lock; sem isso ele incrementaria
        // a cópia destacada (contagem 0) e poderia apagar o incremento do outro.
        verify(repository, times(2)).findComLockByUserIdAndDia(USUARIO_ID, diaSeguinte);
    }

    @Test
    @DisplayName("limite <= 0 desabilita a cota sem tocar no banco")
    void limiteZeroDesabilitaACota() {
        OcrQuotaConsumption resultado = service(0, AGORA).consumir();

        assertEquals(0, resultado.usados());
        assertEquals(0, resultado.limite());
        assertEquals(0, resultado.restantes());
        assertEquals(RESET, resultado.reset());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("corrida na primeira inserção relê a linha vencedora com lock")
    void corridaNaPrimeiraInsercaoRelêComLock() {
        OcrUsage vencedora = uso(0);
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA))
                .thenReturn(Optional.empty(), Optional.of(vencedora));
        when(repository.saveAndFlush(any(OcrUsage.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        OcrQuotaConsumption resultado = service(3, AGORA).consumir();

        assertEquals(1, resultado.usados());
        verify(repository, times(2)).findComLockByUserIdAndDia(USUARIO_ID, DIA);
        verify(repository).save(vencedora);
        // A tentativa de INSERT fez rollback só da transação interna; a
        // transação externa seguiu para reler a linha vencedora.
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    @Test
    @DisplayName("consulta sem consumir não grava e devolve 0 para quem nunca usou")
    void consultaSemConsumirNaoGrava() {
        when(repository.findByUserIdAndDia(USUARIO_ID, DIA)).thenReturn(Optional.empty());

        OcrQuotaConsumption resultado = service(5, AGORA).consultarSemConsumir();

        assertEquals(0, resultado.usados());
        assertEquals(5, resultado.restantes());
        assertEquals(RESET, resultado.reset());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("sem usuário autenticado, falha clara em vez de criar cota órfã")
    void semUserAutenticadoFalha() {
        when(organizacaoContexto.usuarioAutenticado()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service(3, AGORA).consumir());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("devolve uma unidade quando a chamada ao OCR falha")
    void devolveUnidadeConsumida() {
        OcrUsage uso = uso(2);
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA)).thenReturn(Optional.of(uso));

        service(3, AGORA).devolver();

        assertEquals(1, uso.getContagem());
        verify(repository).save(uso);
    }

    @Test
    @DisplayName("devolver nunca deixa a contagem abaixo de zero")
    void devolverNaoDeixaContagemNegativa() {
        OcrUsage uso = uso(0);
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA)).thenReturn(Optional.of(uso));

        service(3, AGORA).devolver();

        assertEquals(0, uso.getContagem());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("devolver sem linha do dia não cria registro")
    void devolverSemLinhaNaoCriaRegistro() {
        when(repository.findComLockByUserIdAndDia(USUARIO_ID, DIA)).thenReturn(Optional.empty());

        service(3, AGORA).devolver();

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("com cota desabilitada, devolver não toca no banco")
    void devolverComCotaDesabilitadaNaoTocaNoBanco() {
        service(0, AGORA).devolver();

        verifyNoInteractions(repository);
    }
}
