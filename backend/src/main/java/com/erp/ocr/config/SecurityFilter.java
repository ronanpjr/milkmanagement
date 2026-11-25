package com.erp.ocr.config; // Place it in the config package

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.service.TokenService;

import java.io.IOException;

@Component
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(SecurityFilter.class);

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository usuarioRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tokenJWT = recoverToken(request);

        if (tokenJWT != null) {
            try {
                String subject = tokenService.getSubject(tokenJWT);
                // Join fetch da organização: OrganizationContext lê
                // usuario.getOrganization() fora de qualquer transação (o
                // filtro não é transacional), e um proxy lazy dispararia
                // LazyInitializationException nesse ponto.
                var optionalUser = usuarioRepository.findByEmailComOrganization(subject);

                if (optionalUser.isPresent()) {
                    UserDetails usuario = optionalUser.get();
                    // Authenticate the user for this request
                    var authentication = new UsernamePasswordAuthenticationToken(usuario, null,
                            usuario.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (RuntimeException e) {
                // Invalid/expired/malformed token: leave the request unauthenticated so it
                // falls through to a 401 on protected routes, instead of a 500.
                logger.debug("Rejected invalid/expired token: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        // Continue the filter chain
        filterChain.doFilter(request, response);
    }

    private String recoverToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        // Só aceita o esquema Bearer (case-insensitive, como manda a RFC 7235).
        // Antes, `replace("Bearer ", "")` removia a ocorrência em qualquer
        // posição e não checava o esquema: um `Authorization: Basic ...` ia
        // parar no parser de JWT em vez de ser ignorado.
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authHeader.substring(7).trim();
        }
        return null;
    }
}