package com.erp.ocr.config;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.erp.ocr.dto.ApiErrorDto;

import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private SecurityFilter securityFilter;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${api.security.cors.origins:http://localhost:3000}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Headers de seguranca basicos. Sem eles a API responde sem
                // protecoes contra sniffing de tipo de conteudo, embedding em
                // iframe e vazamento de URL via Referer.
                // HSTS so tem efeito quando a conexao ja e HTTPS; o TLS em si e
                // terminado no proxy que fica na frente da aplicacao.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                // Sem isto, o entry point padrao do Spring Security para uma API
                // stateless e o Http403ForbiddenEntryPoint: TODA requisicao sem
                // token — ou com token invalido — respondia 403 em vez de 401.
                // Isso quebrava o frontend, que so refaz a sessao em resposta a
                // 401: com 403, um access token expirado nao disparava o refresh
                // e o usuario perdia a sessao em vez de renova-la sozinho.
                //
                // A distincao importa e e mantida:
                //   - 401: NAO autenticado (token ausente, invalido ou expirado);
                //   - 403: autenticado, mas sem permissao (ex.: USER em rota de
                //     ADMIN).
                // E o corpo e o mesmo ApiErrorDto dos demais endpoints, para o
                // cliente nao ter que tratar duas formas de erro.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiErrorEntryPoint())
                        .accessDeniedHandler(apiErrorAccessDeniedHandler()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        // Logout revoga o refresh token apresentado (achado 12):
                        // exige access token válido, por isso NÃO entra no
                        // permitAll de login/refresh. Explícito para que um
                        // futuro permitAll em /api/auth/** não o abra por engano.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").hasRole("ADMIN")
                        // A documentacao da API deixa de ser publica. Swagger UI
                        // estava em permitAll(), o que entregava a um visitante
                        // nao autenticado o contrato completo da API (todas as
                        // rotas e todos os campos dos DTOs). Em producao,
                        // API_DOCS_ENABLED=false desliga a exposicao por completo.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                            .hasRole("ADMIN")
                        // /error e a rota para onde o container encaminha o erro
                        // (404/405/500). Exigir ROLE_ADMIN aqui mascarava o status
                        // real para um USER: em vez do 404/405 verdadeiro, ele
                        // recebia o 403 do /error. A rota nao vaza nada — so o
                        // corpo ApiErrorDto ja tratado pelo GlobalExceptionHandler.
                        .requestMatchers("/error").permitAll()
                        // O health check do CD consulta /actuator/health sem
                        // cabecalho de autenticacao (curl na VM, antes de
                        // trocar o trafego). Com o anyRequest() abaixo, dava
                        // 403, o script nunca via "status":"UP" e o primeiro
                        // deploy revertia sozinho.
                        //
                        // /actuator/prometheus e /actuator/metrics tambem sao
                        // publicos SEM token, e isso e deliberado: o Actuator
                        // roda na porta separada 8081, que o compose publica
                        // apenas em 127.0.0.1:8081 no host. A protecao dessas
                        // rotas e o bind em loopback + a porta nao publicada
                        // externamente (nao ha firewall para 8081 e o Caddy
                        // nao a proxya). Elas nao existem na 8080. Se um dia a
                        // 8081 for publicada fora do loopback, este permitAll
                        // precisa virar autenticacao.
                        //
                        // Os demais endpoints do actuator continuam fechados
                        // por anyRequest(): /actuator/env e
                        // /actuator/configproperties jogam variaveis de
                        // ambiente e properties — inclusive a configuracao do
                        // banco — para qualquer um que chegue ate eles.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Resposta para requisicao <strong>nao autenticada</strong>: token ausente,
     * invalido ou expirado. O {@code SecurityFilter} (customizado) ja limpa o
     * contexto quando o token nao vale, e o pedido segue como anonimo ate
     * aqui — e o que chega e 401, nao 403.
     */
    private AuthenticationEntryPoint apiErrorEntryPoint() {
        return (request, response, authException) -> writeApiError(response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "Autenticacao necessaria: token ausente, invalido ou expirado.",
                "Unauthorized", request.getRequestURI());
    }

    /**
     * Resposta para requisicao <strong>autenticada sem permissao</strong>. E
     * 403 de proposito: o usuario esta autenticado, o que falta e autorizacao —
     * nao credencial. Confundir os dois esconderia a diferenca do cliente.
     */
    private AccessDeniedHandler apiErrorAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeApiError(response,
                HttpServletResponse.SC_FORBIDDEN,
                "Acesso negado: voce nao tem permissao para esta operacao.",
                "Forbidden", request.getRequestURI());
    }

    /**
     * Escreve o corpo de erro no formato {@link ApiErrorDto}, o mesmo dos
     * endpoints tratados pelo {@link GlobalExceptionHandler}. Sem isto, essas
     * respostas sairiam no JSON padrao do Spring Security, e o cliente teria
     * duas formas de erro distintas para tratar.
     */
    private void writeApiError(HttpServletResponse response, int status, String message, String error, String path)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiErrorDto.of(message, status, error, path));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Split origins from properties
        String[] origins = corsAllowedOrigins.split(",");
        for (String origin : origins) {
            configuration.addAllowedOrigin(origin.trim());
        }

        // Electron desktop app carrega a pagina via file:// (app empacotado).
        // Navegadores nunca enviam "file://" como Origin: para uma pagina de
        // arquivo local eles enviam a origem literal "null". Sem ela o preflight
        // do app empacotado era rejeitado. Ainda assim o escopo e estreito
        // (lista explicita, sem "*"), e sem credenciais de cookie o ganho de um
        // iframe sandboxed que tambem manda "null" e nulo.
        configuration.addAllowedOrigin("file://");
        configuration.addAllowedOrigin("null");

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        // Auth uses a Bearer token in the Authorization header, not cookies, so
        // credentialed (cookie-based) CORS is unnecessary and can be disabled.
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}