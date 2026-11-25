package com.erp.ocr.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.erp.ocr.dto.ApiErrorDto;
import com.erp.ocr.dto.LoginDto;
import com.erp.ocr.dto.UserProfileDto;
import com.erp.ocr.dto.RefreshTokenRequestDto;
import com.erp.ocr.dto.RegisterUserDto;
import com.erp.ocr.dto.TokenDto;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.service.AuthService;
import com.erp.ocr.service.LoginRateLimiterService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository usuarioRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthService authService;

    @Autowired
    private LoginRateLimiterService loginRateLimiter;

    @Autowired
    private OrganizationContext organizacaoContexto;

    @Autowired
    private com.erp.ocr.repository.OrganizationRepository organizacaoRepository;

    @PostMapping("/register")
    public ResponseEntity<Void> registrar(@RequestBody @Valid RegisterUserDto dto) {
        // Email já existente não pode ser distinguível por status: 400 vs 201
        // permitia enumerar quais e-mails têm conta. A rota é ADMIN-only, mas
        // responder igual nos dois casos não custa nada.
        if (usuarioRepository.findByEmail(dto.email()).isPresent()) {
            return ResponseEntity.status(201).build();
        }

        String senhaCriptografada = passwordEncoder.encode(dto.senha());
        com.erp.ocr.model.Role role = dto.role() != null ? dto.role()
                : com.erp.ocr.model.Role.USER;
        // O novo usuário entra na organização de quem o cadastrou: é assim que
        // aadmin gerencia mais de uma organização sem misturar as carteiras.
        var organizacaoAtual = organizacaoContexto.usuarioAutenticado();
        if (organizacaoAtual == null || organizacaoAtual.getOrganization() == null) {
            throw new IllegalStateException(
                    "Não há usuário autenticado com organização definida para criar outro usuário.");
        }
        User novoUser = new User();
        novoUser.setEmail(dto.email());
        novoUser.setNome(dto.nome());
        novoUser.setSenha(senhaCriptografada);
        novoUser.setRole(role);
        novoUser.setOrganization(organizacaoAtual.getOrganization());
        usuarioRepository.save(novoUser);

        return ResponseEntity.status(201).build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginDto dto, HttpServletRequest request) {
        // B7: basic brute-force protection - lock out a client (by IP) after too many
        // failed attempts within a time window, instead of allowing unlimited guesses.
        String rateLimitKey = resolveClientIp(request);
        if (loginRateLimiter.isBlocked(rateLimitKey)) {
            ApiErrorDto body = ApiErrorDto.of(
                    "Muitas tentativas de login falharam. Tente novamente mais tarde.",
                    HttpStatus.TOO_MANY_REQUESTS.value(), HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                    request.getRequestURI());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }

        try {
            var authenticationToken = new UsernamePasswordAuthenticationToken(dto.email(), dto.senha());
            Authentication authentication = authenticationManager.authenticate(authenticationToken);

            loginRateLimiter.registerSuccess(rateLimitKey);

            User usuarioAutenticado = (User) authentication.getPrincipal();
            TokenDto tokens = authService.emitirTokens(usuarioAutenticado);
            return ResponseEntity.ok(tokens);
        } catch (AuthenticationException e) {
            loginRateLimiter.registerFailure(rateLimitKey);
            throw e;
        }
    }

    /**
     * Resolve o IP do cliente para fins de rate limiting.
     * <p>
     * Usa sempre {@code getRemoteAddr()}, que já é o IP real da conexão. Com
     * {@code server.forward-headers-strategy=native} (application.properties),
     * o RemoteIpValve do Tomcat substitui esse valor pelo cliente original
     * apenas quando a conexão vem de um proxy interno
     * ({@code server.tomcat.remoteip.internal-proxies}: as faixas do Docker).
     * <p>
     * O header {@code X-Forwarded-For} <b>não</b> é lido aqui. Enquanto a porta
     * 8080 estiver pública, qualquer cliente pode falar direto com a aplicação
     * e forjar o header a cada tentativa, caindo num bucket novo e anulando o
     * limite de tentativas (B7). O header cru só é confiável depois de o
     * container validar quem é o proxy — e é exatamente isso que o valve faz.
     */
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Perfil do usuário autenticado.
     * <p>
     * O frontend precisa disto para esconder o que é exclusivo de ADMIN — hoje
     * o item "Usuários" aparece na sidebar para qualquer um, e o 403 só vem
     * quando o usuário clica. Devolve também a organização, para o cliente
     * saber a que carteira os dados pertencem.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> me() {
        User usuario = organizacaoContexto.usuarioAutenticado();
        if (usuario == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("Não autenticado.");
        }
        return ResponseEntity.ok(new UserProfileDto(
                usuario.getEmail(),
                usuario.getNome(),
                usuario.getRole().name(),
                usuario.getOrganization() != null ? usuario.getOrganization().getNome() : null,
                null));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenDto> refresh(@RequestBody @Valid RefreshTokenRequestDto dto) {
        // Um refresh token expirado/invalido e um evento esperado e frequente
        // (expiracao normal de sessao), nao um erro do servidor. Antes, a
        // RuntimeException de TokenService caia no handler generico do
        // GlobalExceptionHandler e o cliente recebia 500 em vez de 401 — o
        // frontend nao conseguia distinguir "faca login" de "servidor quebrado".
        //
        // A rotação com estado no banco (achado 12) vive no AuthService: valida
        // por hash, revoga o token usado e emite um par novo; reuso de um token
        // já revogado derruba todas as sessões do usuário. O contrato de
        // resposta (TokenDto) permanece o mesmo.
        return ResponseEntity.ok(authService.rotacionar(dto.refreshToken()));
    }

    /**
     * Logout: revoga o refresh token apresentado (rotação com estado no banco,
     * achado 12). Autenticado — o {@link SecurityConfig} não abre esta rota em
     * {@code permitAll}. Não devolve corpo.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody @Valid RefreshTokenRequestDto dto) {
        authService.revogar(dto.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
