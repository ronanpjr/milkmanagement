package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobertura de {@link TokenService}.
 * <p>
 * Cobre os dois achados de segurança que vivem aqui:
 * <ul>
 * <li><b>B1</b> — segredo com menos de 256 bits precisa derrubar o boot, senão
 * a aplicação sobe com uma chave fraca que dá para forjar token;</li>
 * <li><b>B6</b> — um refresh token não pode ser aceito como access token (e
 * vice-versa), senão o refresh de longa duração vira um access token permanente.</li>
 * </ul>
 * Observação: o JJWT 0.12 lança exceções <i>unchecked</i> (JwtException e
 * subclasses), então o {@code catch (Exception)} de {@code getSubject} é
 * inalcançável e a mensagem que chega ao cliente é a do próprio JJWT. Os testes
 * de rejeição por assinatura/expiração/formato verificam o contrato estável
 * (token rejeitado com RuntimeException) sem fixar essa mensagem.
 */
class TokenServiceTest {

    private static final String SEGREDO_VALIDO = "segredo-de-teste-com-256-bits-minimos-1234";
    private static final long EXPIRACAO_MS = 3_600_000L;
    private static final long EXPIRACAO_REFRESH_MS = 604_800_000L;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = novoTokenService(SEGREDO_VALIDO, EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);
    }

    private static TokenService novoTokenService(String segredo, long expiraEmMs, long refreshEmMs) {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "secret", segredo);
        ReflectionTestUtils.setField(service, "expiration", expiraEmMs);
        ReflectionTestUtils.setField(service, "refreshExpiration", refreshEmMs);
        return service;
    }

    private static User usuario() {
        // Sem construtor de todos os argumentos: o User ganhou a coluna
        // organizacao_id (NOT NULL) e montar por posicao seria fragile —
        // a organizacao teria que entrar na posicao correta, sem o compilador
        // ajudar. Setters tambem evitam que o teste quebre de novo a cada
        // campo novo na entidade.
        User u = new User();
        u.setId(7L);
        u.setEmail("joao@erpocr.com");
        u.setNome("João");
        u.setSenha("hash");
        u.setRole(Role.USER);
        Organization org = new Organization("Cooperativa Teste");
        org.setId(1L);
        u.setOrganization(org);
        return u;
    }

    private static Claims claimsDe(String token, String segredo) {
        SecretKey key = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // ------------------------------------------------------------------
    // B1 — o segredo precisa ter 256 bits
    // ------------------------------------------------------------------

    @Test
    void validateSecret_DeveAceitarSegredoComExatamenteTrintaEDoisBytes() {
        TokenService service = novoTokenService("12345678901234567890123456789012", EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);

        assertDoesNotThrow(service::validateSecret);
    }

    @Test
    void validateSecret_DeveRejeitarSegredoComMenosDeTrintaEDoisBytes() {
        // 31 bytes: um byte a menos e já é fraco para HS256.
        TokenService service = novoTokenService("1234567890123456789012345678901", EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);

        IllegalStateException erro = assertThrows(IllegalStateException.class, service::validateSecret);

        assertTrue(erro.getMessage().contains("pelo menos 32 bytes"));
        assertTrue(erro.getMessage().contains("Tamanho atual: 31 bytes"));
    }

    @Test
    void validateSecret_DeveRejeitarSegredoVazio() {
        TokenService service = novoTokenService("", EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);

        IllegalStateException erro = assertThrows(IllegalStateException.class, service::validateSecret);

        assertTrue(erro.getMessage().contains("não pode estar vazio"));
    }

    @Test
    void validateSecret_DeveRejeitarSegredoEmBrancoOuNulo() {
        TokenService emBranco = novoTokenService("     ", EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);
        TokenService nulo = novoTokenService(null, EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);

        assertThrows(IllegalStateException.class, emBranco::validateSecret);
        assertThrows(IllegalStateException.class, nulo::validateSecret);
    }

    @Test
    void boot_DeveFalharQuandoOSegredoTemMenosDe256Bits() {
        // O @PostConstruct de TokenService roda na criação do bean: um segredo
        // curto tem que impedir a aplicação de subir, não só gerar warning.
        new ApplicationContextRunner()
                .withUserConfiguration(ConTokenService.class)
                .withPropertyValues(
                        "api.security.token.secret=segredo-curto",
                        "api.security.token.expiration=3600000")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null, "o contexto não deveria subir");
                    assertTrue(temCausaDeSegredoCurto(context.getStartupFailure()));
                });
    }

    @Test
    void boot_DeveSubirComSegredoValido() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConTokenService.class)
                .withPropertyValues(
                        "api.security.token.secret=" + SEGREDO_VALIDO,
                        "api.security.token.expiration=3600000")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(TokenService.class));
                });
    }

    private static boolean temCausaDeSegredoCurto(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof IllegalStateException
                    && atual.getMessage() != null
                    && atual.getMessage().contains("pelo menos 32 bytes")) {
                return true;
            }
        }
        return false;
    }

    @Configuration
    static class ConTokenService {
        @Bean
        TokenService tokenService() {
            return new TokenService();
        }
    }

    // ------------------------------------------------------------------
    // Emissão dos tokens
    // ------------------------------------------------------------------

    @Test
    void gerarToken_DeveEmitirAccessTokenComEmailEUserId() {
        String token = tokenService.gerarToken(usuario());

        Claims claims = claimsDe(token, SEGREDO_VALIDO);
        assertEquals("joao@erpocr.com", claims.getSubject());
        assertEquals(7L, claims.get("userId", Number.class).longValue());
        assertEquals("access", claims.get("type", String.class));
        assertEquals("ERP AI OCR API", claims.getIssuer());
    }

    @Test
    void gerarToken_DeveExpirarNoPrazoConfigurado() {
        Claims claims = claimsDe(tokenService.gerarToken(usuario()), SEGREDO_VALIDO);

        long validadeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(EXPIRACAO_MS, validadeMs);
    }

    @Test
    void gerarRefreshToken_DeveEmitirTokenDoTipoRefreshComValidadeDeSeteDias() {
        Claims claims = claimsDe(tokenService.gerarRefreshToken(usuario()), SEGREDO_VALIDO);

        assertEquals("refresh", claims.get("type", String.class));
        assertEquals("joao@erpocr.com", claims.getSubject());
        long validadeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(EXPIRACAO_REFRESH_MS, validadeMs);
        assertEquals(7L, validadeMs / 86_400_000L);
    }

    @Test
    void accessERefreshToken_DeveSerTokensDiferentes() {
        User usuario = usuario();

        assertNotEquals(tokenService.gerarToken(usuario), tokenService.gerarRefreshToken(usuario));
    }

    // ------------------------------------------------------------------
    // B6 — refresh token não é access token
    // ------------------------------------------------------------------

    @Test
    void getSubject_DeveRejeitarRefreshTokenApresentadoComoAccessToken() {
        String refreshToken = tokenService.gerarRefreshToken(usuario());

        RuntimeException erro = assertThrows(RuntimeException.class, () -> tokenService.getSubject(refreshToken));

        assertEquals("Token não é um access token!", erro.getMessage());
    }

    @Test
    void getSubjectFromRefreshToken_DeveRejeitarAccessTokenApresentadoComoRefreshToken() {
        String accessToken = tokenService.gerarToken(usuario());

        RuntimeException erro = assertThrows(RuntimeException.class,
                () -> tokenService.getSubjectFromRefreshToken(accessToken));

        assertEquals("Token não é um refresh token!", erro.getMessage());
    }

    @Test
    void getSubject_DeveAceitarAccessTokenValido() {
        assertEquals("joao@erpocr.com", tokenService.getSubject(tokenService.gerarToken(usuario())));
    }

    @Test
    void getSubjectFromRefreshToken_DeveAceitarRefreshTokenValido() {
        assertEquals("joao@erpocr.com",
                tokenService.getSubjectFromRefreshToken(tokenService.gerarRefreshToken(usuario())));
    }

    // ------------------------------------------------------------------
    // Rejeições: assinatura, expiração e lixo
    // ------------------------------------------------------------------

    @Test
    void getSubject_DeveRejeitarTokenAssinadoComOutroSegredo() {
        TokenService outro = novoTokenService("outro-segredo-also-com-32-bytes-ok", EXPIRACAO_MS, EXPIRACAO_REFRESH_MS);
        String tokenDeOutro = outro.gerarToken(usuario());

        // Rejeitado com exceção (o token do outro segredo não é aceito como sessão válida).
        assertThrows(RuntimeException.class, () -> tokenService.getSubject(tokenDeOutro));
    }

    @Test
    void getSubject_DeveRejeitarTokenAdulterado() {
        String token = tokenService.gerarToken(usuario());
        String adulterado = token.substring(0, token.length() - 4)
                + (token.endsWith("abcd") ? "efgh" : "abcd");

        assertThrows(RuntimeException.class, () -> tokenService.getSubject(adulterado));
    }

    @Test
    void getSubject_DeveRejeitarTokenExpirado() {
        TokenService expirado = novoTokenService(SEGREDO_VALIDO, -60_000L, EXPIRACAO_REFRESH_MS);
        String tokenVelho = expirado.gerarToken(usuario());

        assertThrows(RuntimeException.class, () -> tokenService.getSubject(tokenVelho));
    }

    @Test
    void getSubjectFromRefreshToken_DeveRejeitarRefreshTokenExpirado() {
        TokenService expirado = novoTokenService(SEGREDO_VALIDO, -60_000L, -60_000L);
        String refreshVelho = expirado.gerarRefreshToken(usuario());

        assertThrows(RuntimeException.class, () -> tokenService.getSubjectFromRefreshToken(refreshVelho));
    }

    @Test
    void getSubject_DeveRejeitarTokenMalFormado() {
        assertThrows(RuntimeException.class, () -> tokenService.getSubject("nao-e-um-jwt"));
    }

    @Test
    void getSubjectFromRefreshToken_DeveRejeitarTokenMalFormado() {
        assertThrows(RuntimeException.class, () -> tokenService.getSubjectFromRefreshToken("nao-e-um-jwt"));
    }

    @Test
    void getSubject_DeveRejeitarTokenSemClaimDeTipo() {
        // Token assinado com o mesmo segredo, porém sem "type": não pode passar
        // como access token só porque a assinatura confere.
        String semTipo = Jwts.builder()
                .subject("intruso@erpocr.com")
                .issuer("ERP AI OCR API")
                .issuedAt(java.sql.Timestamp.from(java.time.Instant.now()))
                .expiration(java.sql.Timestamp.from(java.time.Instant.now().plusMillis(EXPIRACAO_MS)))
                .signWith(Keys.hmacShaKeyFor(SEGREDO_VALIDO.getBytes(StandardCharsets.UTF_8)))
                .compact();

        RuntimeException erro = assertThrows(RuntimeException.class, () -> tokenService.getSubject(semTipo));

        assertEquals("Token não é um access token!", erro.getMessage());
    }
}
