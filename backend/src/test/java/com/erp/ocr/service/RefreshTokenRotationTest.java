package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.dto.TokenDto;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.RefreshToken;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.RefreshTokenRepository;
import com.erp.ocr.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rotação de refresh token com estado no banco (achado 12).
 *
 * <p>
 * Usa @SpringBootTest (e não mocks) de propósito: o que importa é que a
 * rotação, a revogação e a detecção de reuso realmente persistam e sejam
 * lidas de volta do banco — um mock de repositório passaria mesmo com a
 * lógica errada. Roda contra H2, com o schema gerado pela entidade
 * (ddl-auto=create-drop; o Flyway está desligado no perfil de teste).
 */
@SpringBootTest
@Transactional
@DisplayName("Rotação de refresh token com estado no banco")
class RefreshTokenRotationTest {

    @MockitoBean
    private com.erp.ocr.service.ocr.GeminiOcrService geminiOcrService;
    @MockitoBean
    private com.erp.ocr.service.AzureOcrService azureOcrService;

    @Autowired
    private AuthService authService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository usuarioRepository;
    @Autowired
    private OrganizationRepository organizacaoRepository;

    private Organization org;
    private User usuario;

    @BeforeEach
    void prepararCenario() {
        org = organizacaoRepository.save(new Organization("Org Refresh " + UUID.randomUUID()));
        usuario = criarUser("refresh@teste.com", org, Role.USER);
    }

    private User criarUser(String email, Organization organizacao, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setNome("Usuário Refresh");
        u.setSenha("{noop}senha");
        u.setRole(role);
        u.setOrganization(organizacao);
        return usuarioRepository.save(u);
    }

    private RefreshToken buscar(String token) {
        return refreshTokenRepository.findByTokenHash(tokenService.hashToken(token))
                .orElseThrow(() -> new AssertionError("refresh token não registrado"));
    }

    // ------------------------------------------------------------------
    // Hash / emissão
    // ------------------------------------------------------------------

    @Test
    @DisplayName("emitirTokens registra o hash do refresh, nunca o token cru")
    void emitirTokensPersisteApenasOHash() {
        TokenDto tokens = authService.emitirTokens(usuario);

        RefreshToken registro = buscar(tokens.refreshToken());
        assertEquals(tokenService.hashToken(tokens.refreshToken()), registro.getTokenHash());
        assertNotEquals(tokens.refreshToken(), registro.getTokenHash());
        assertTrue(registro.isAtivo());
        assertTrue(registro.getExpiraEm().isAfter(LocalDateTime.now()));
    }

    @Test
    @DisplayName("hash do token é determinístico (busca por hash encontra a linha)")
    void hashTokenEDeterministico() {
        assertEquals(tokenService.hashToken("abc"), tokenService.hashToken("abc"));
        assertEquals(64, tokenService.hashToken("abc").length());
    }

    // ------------------------------------------------------------------
    // Rotação
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rotação revoga o token usado e emite um par novo")
    void rotacaoRevogaOAntigoEEmiteParNovo() {
        TokenDto inicial = authService.emitirTokens(usuario);

        TokenDto novo = authService.rotacionar(inicial.refreshToken());

        assertNotEquals(inicial.refreshToken(), novo.refreshToken());
        assertNotEquals(inicial.token(), novo.token());
        assertFalse(buscar(inicial.refreshToken()).isAtivo(), "o token usado na rotação deve ser revogado");
        assertTrue(buscar(novo.refreshToken()).isAtivo(), "o token novo deve ficar ativo");
    }

    // ------------------------------------------------------------------
    // Reuse-detection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reapresentar um token revogado revoga TODAS as sessões do usuário")
    void reusoRevogaTodaACadeia() {
        TokenDto t0 = authService.emitirTokens(usuario);
        TokenDto t1 = authService.rotacionar(t0.refreshToken());
        assertTrue(buscar(t1.refreshToken()).isAtivo());

        // Reuso de t0 (já revogado): sinal de vazamento.
        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(t0.refreshToken()));

        assertFalse(buscar(t1.refreshToken()).isAtivo(), "a sessão seguinte também deve cair na revogação em massa");
        assertTrue(refreshTokenRepository.findByUserIdAndRevogadoEmIsNull(usuario.getId()).isEmpty(),
                "não pode sobrar nenhuma sessão ativa depois da detecção de reuso");

        // E a sessão revogada pela cadeia não pode mais ser rotacionada.
        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(t1.refreshToken()));
    }

    // ------------------------------------------------------------------
    // Expirado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refresh token expirado (JWT) responde 401")
    void refreshTokenExpiradoResponde401() {
        TokenService expirado = tokenServiceExpirado();
        String refreshExpirado = expirado.gerarRefreshToken(usuario);

        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(refreshExpirado));
    }

    @Test
    @DisplayName("registro expirado no banco responde 401 e é revogado")
    void registroExpiradoNoBancoResponde401() {
        // JWT ainda válido, mas a linha no banco já venceu: o banco é a fonte
        // da verdade sobre a expiração.
        String refresh = tokenService.gerarRefreshToken(usuario);
        RefreshToken registro = new RefreshToken();
        registro.setUser(usuario);
        registro.setTokenHash(tokenService.hashToken(refresh));
        registro.setCriadoEm(LocalDateTime.now().minusDays(8));
        registro.setExpiraEm(LocalDateTime.now().minusDays(1));
        refreshTokenRepository.save(registro);

        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(refresh));
        assertFalse(buscar(refresh).isAtivo());
    }

    @Test
    @DisplayName("JWT válido mas não registrado no banco responde 401")
    void tokenNaoRegistradoResponde401() {
        String refresh = tokenService.gerarRefreshToken(usuario);

        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(refresh));
    }

    @Test
    @DisplayName("refresh token malformado responde 401")
    void tokenMalformadoResponde401() {
        assertThrows(BadCredentialsException.class, () -> authService.rotacionar("nao-e-um-jwt"));
    }

    // ------------------------------------------------------------------
    // Logout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("logout revoga o token apresentado")
    void logoutRevogaOToken() {
        TokenDto tokens = authService.emitirTokens(usuario);

        authService.revogar(tokens.refreshToken());

        assertFalse(buscar(tokens.refreshToken()).isAtivo());
        assertThrows(BadCredentialsException.class, () -> authService.rotacionar(tokens.refreshToken()));
    }

    @Test
    @DisplayName("logout é idempotente e tolerante a token desconhecido")
    void logoutIdempotenteETolerante() {
        TokenDto tokens = authService.emitirTokens(usuario);

        authService.revogar(tokens.refreshToken());
        assertDoesNotThrow(() -> authService.revogar(tokens.refreshToken()));
        assertDoesNotThrow(() -> authService.revogar("nao-e-um-jwt"));
    }

    // ------------------------------------------------------------------
    // Isolamento entre organizações
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fluxo de USER de outra organização segue inalterado e isolado")
    void fluxoDeOutraOrganizationFicaInalterado() {
        Organization outra = organizacaoRepository.save(new Organization("Outra Org " + UUID.randomUUID()));
        User userOutra = criarUser("outra@teste.com", outra, Role.USER);

        TokenDto doUser = authService.emitirTokens(usuario);
        TokenDto daOutra = authService.emitirTokens(userOutra);

        TokenDto rotacionadoOutra = authService.rotacionar(daOutra.refreshToken());

        assertTrue(buscar(rotacionadoOutra.refreshToken()).isAtivo());
        assertEquals(outra.getId(), buscar(rotacionadoOutra.refreshToken()).getUser().getOrganization().getId(),
                "a rotação não pode trocar a organização do usuário");
        assertTrue(buscar(doUser.refreshToken()).isAtivo(),
                "a rotação de uma organização não pode revogar sessões de outra");
    }

    private TokenService tokenServiceExpirado() {
        String secret = (String) ReflectionTestUtils.getField(tokenService, "secret");
        TokenService expirado = new TokenService();
        ReflectionTestUtils.setField(expirado, "secret", secret);
        ReflectionTestUtils.setField(expirado, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(expirado, "refreshExpiration", -60_000L);
        return expirado;
    }
}
