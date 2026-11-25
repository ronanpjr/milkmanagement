package com.erp.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.UserRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserRepository usuarioRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void loadUserByUsername_DeveRetornarUser_QuandoExistir() {
        String email = "test@email.com";
        User usuario = new User();
        usuario.setId(1L);
        usuario.setEmail(email);
        usuario.setNome("Nome");
        usuario.setSenha("password");
        usuario.setRole(com.erp.ocr.model.Role.USER);
        usuario.setOrganization(new com.erp.ocr.model.Organization("Org Teste"));
        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(usuario));

        UserDetails userDetails = authService.loadUserByUsername(email);

        assertNotNull(userDetails);
        assertEquals(email, userDetails.getUsername());
    }

    @Test
    void loadUserByUsername_DeveLancarExcecao_QuandoNaoExistir() {
        String email = "naoexiste@email.com";
        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> authService.loadUserByUsername(email));
    }
}
