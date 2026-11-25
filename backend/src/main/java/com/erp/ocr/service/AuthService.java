package com.erp.ocr.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.dto.TokenDto;
import com.erp.ocr.model.RefreshToken;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.RefreshTokenRepository;
import com.erp.ocr.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuthService implements UserDetailsService {

    @Autowired
    private UserRepository repository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Agora podemos retornar o objeto User diretamente
        return repository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado com o e-mail: " + username));
    }

    /**
     * Emite um par access + refresh e registra o refresh no banco (por hash).
     * Ponto único de emissão: login e rotação passam por aqui.
     */
    @Transactional
    public TokenDto emitirTokens(User usuario) {
        String accessToken = tokenService.gerarToken(usuario);
        String refreshToken = tokenService.gerarRefreshToken(usuario);
        registrarRefreshToken(usuario, refreshToken);
        return new TokenDto(accessToken, refreshToken);
    }

    /**
     * Rotação do refresh token (achado 12).
     * <p>
     * O token apresentado é validado pela assinatura/expiração do JWT e pelo
     * hash no banco: precisa existir, não estar revogado e não estar expirado.
     * No sucesso, o token usado é revogado e um par novo é emitido.
     * <p>
     * <b>Reuse-detection:</b> reapresentar um token já revogado é sinal de que
     * ele vazou — o dono legítimo já o trocou. Nesse caso todas as sessões do
     * usuário são revogadas, e não apenas a reapresentada.
     * <p>
     * {@code noRollbackFor} é essencial: o caminho de reuso revoga a cadeia e
     * depois lança {@code BadCredentialsException}. Sem ele, o rollback
     * desfaria a revogação e a detecção de reuso não teria efeito nenhum.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public TokenDto rotacionar(String refreshToken) {
        String email;
        try {
            email = tokenService.getSubjectFromRefreshToken(refreshToken);
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Refresh token inválido ou expirado.", e);
        }

        RefreshToken persistido = refreshTokenRepository.findByTokenHash(tokenService.hashToken(refreshToken))
                .orElseThrow(() -> new BadCredentialsException("Refresh token inválido ou expirado."));

        LocalDateTime agora = LocalDateTime.now();

        if (!persistido.isAtivo()) {
            revogarTodasAsSessoes(persistido.getUser().getId(), agora);
            throw new BadCredentialsException("Refresh token reutilizado; todas as sessões foram revogadas.");
        }

        if (persistido.isExpirado(agora)) {
            persistido.setRevogadoEm(agora);
            refreshTokenRepository.save(persistido);
            throw new BadCredentialsException("Refresh token inválido ou expirado.");
        }

        User usuario = repository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Usuário do refresh token não encontrado."));

        persistido.setRevogadoEm(agora);
        refreshTokenRepository.save(persistido);
        return emitirTokens(usuario);
    }

    /**
     * Logout: revoga o refresh token apresentado. Idempotente e tolerante a
     * token desconhecido — repetir o logout não deve gerar erro.
     */
    @Transactional
    public void revogar(String refreshToken) {
        refreshTokenRepository.findByTokenHash(tokenService.hashToken(refreshToken))
                .filter(RefreshToken::isAtivo)
                .ifPresent(persistido -> {
                    persistido.setRevogadoEm(LocalDateTime.now());
                    refreshTokenRepository.save(persistido);
                });
    }

    private void registrarRefreshToken(User usuario, String refreshToken) {
        LocalDateTime agora = LocalDateTime.now();
        RefreshToken registro = new RefreshToken();
        registro.setUser(usuario);
        registro.setTokenHash(tokenService.hashToken(refreshToken));
        registro.setCriadoEm(agora);
        registro.setExpiraEm(agora.plus(Duration.ofMillis(tokenService.getRefreshExpirationMillis())));
        refreshTokenRepository.save(registro);
    }

    private void revogarTodasAsSessoes(Long usuarioId, LocalDateTime agora) {
        List<RefreshToken> ativos = refreshTokenRepository.findByUserIdAndRevogadoEmIsNull(usuarioId);
        for (RefreshToken ativo : ativos) {
            ativo.setRevogadoEm(agora);
        }
        refreshTokenRepository.saveAll(ativos);
    }
}
