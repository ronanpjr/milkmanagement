package com.erp.ocr.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.erp.ocr.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenService {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits, required for HS256

    @Value("${api.security.token.secret}")
    private String secret;

    @Value("${api.security.token.expiration}")
    private long expiration;

    @Value("${api.security.refresh-token.expiration:604800000}")
    private long refreshExpiration;

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "api.security.token.secret (JWT_SECRET) não pode estar vazio ou em branco.");
        }
        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "api.security.token.secret (JWT_SECRET) deve ter pelo menos " + MIN_SECRET_BYTES
                            + " bytes (256 bits) para uso com HS256. Tamanho atual: " + secretBytes + " bytes.");
        }
    }

    public String gerarToken(User usuario) {
        SecretKey secretKey = getSecretKey();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(usuario.getEmail())
                .claim("userId", usuario.getId())
                .claim("type", "access")
                .issuer("ERP AI OCR API")
                .issuedAt(java.sql.Timestamp.from(Instant.now()))
                .expiration(java.sql.Timestamp.from(Instant.now().plusMillis(expiration)))
                .signWith(secretKey)
                .compact();
    }

    public String gerarRefreshToken(User usuario) {
        SecretKey secretKey = getSecretKey();
        // O "jti" (UUID) garante que dois tokens emitidos para o mesmo usuário
        // no mesmo segundo não coincidam: iat/exp têm precisão de segundos, e
        // a rotação guarda o hash com UNIQUE. Sem ele, o segundo login do
        // mesmo segundo colidiria na tabela.
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(usuario.getEmail())
                .claim("userId", usuario.getId())
                .claim("type", "refresh")
                .issuer("ERP AI OCR API")
                .issuedAt(java.sql.Timestamp.from(Instant.now()))
                .expiration(java.sql.Timestamp.from(Instant.now().plusMillis(refreshExpiration)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * SHA-256 em hexadecimal do token. É este valor que vai para o banco; o
     * token cru nunca é persistido nem logado. Determinístico, então a busca
     * por hash encontra a linha a partir do token apresentado.
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; a ausência é erro de ambiente.
            throw new IllegalStateException("Algoritmo SHA-256 indisponível.", e);
        }
    }

    /** Validade configurada do refresh token, em milissegundos. */
    public long getRefreshExpirationMillis() {
        return refreshExpiration;
    }

    public String getSubject(String tokenJWT) {
        try {
            SecretKey secretKey = getSecretKey();
            var claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(tokenJWT)
                    .getPayload();

            String type = claims.get("type", String.class);
            if (!"access".equals(type)) {
                throw new RuntimeException("Token não é um access token!");
            }

            return claims.getSubject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Token JWT inválido ou expirado!");
        }
    }

    public String getSubjectFromRefreshToken(String refreshToken) {
        try {
            SecretKey secretKey = getSecretKey();
            var claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(refreshToken)
                    .getPayload();

            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                throw new RuntimeException("Token não é um refresh token!");
            }

            return claims.getSubject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Refresh token inválido ou expirado!");
        }
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}