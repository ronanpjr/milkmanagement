package com.erp.ocr.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo HTTP de /api/auth/login -> /api/auth/refresh -> /api/auth/logout
 * (achado 12). Confirma o contrato do refresh (TokenDto inalterado), o 401 na
 * detecção de reuso, a revogação em massa da cadeia e o 204 do logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Endpoints de refresh e logout")
class AuthRefreshLogoutControllerTest {

    private static final String SENHA = "senha-forte-123";

    @MockitoBean
    private com.erp.ocr.service.ocr.GeminiOcrService geminiOcrService;
    @MockitoBean
    private com.erp.ocr.service.AzureOcrService azureOcrService;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OrganizationRepository organizacaoRepository;
    @Autowired
    private UserRepository usuarioRepository;

    private String email;

    @BeforeEach
    void prepararUser() {
        email = "http-" + UUID.randomUUID() + "@refresh.test";
        Organization org = organizacaoRepository.save(new Organization("Org HTTP " + UUID.randomUUID()));
        User u = new User();
        u.setEmail(email);
        u.setNome("Usuário HTTP");
        u.setSenha(passwordEncoder.encode(SENHA));
        u.setRole(Role.USER);
        u.setOrganization(org);
        usuarioRepository.save(u);
    }

    private record Tokens(String access, String refresh) {
    }

    private Tokens login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "senha", SENHA))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Tokens(json.get("token").asText(), json.get("refreshToken").asText());
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("rotação devolve par novo com o mesmo contrato e reuso derruba a cadeia")
    void rotacaoEReuso() throws Exception {
        Tokens t0 = login();

        MvcResult resposta = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", t0.refresh()))))
                .andExpect(status().isOk())
                // Contrato inalterado: mesmos campos do TokenDto.
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        String t1Refresh = objectMapper.readTree(resposta.getResponse().getContentAsString())
                .get("refreshToken").asText();

        // Reuso do refresh já rotacionado: 401.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", t0.refresh()))))
                .andExpect(status().isUnauthorized());

        // A cadeia inteira caiu: o token seguinte também é 401.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", t1Refresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revoga o refresh token e ele deixa de rotacionar")
    void logoutRevoga() throws Exception {
        Tokens tokens = login();

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refresh()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refresh()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout exige autenticação")
    void logoutExigeAutenticacao() throws Exception {
        Tokens tokens = login();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refresh()))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("refresh com token inválido responde 401")
    void refreshComTokenInvalido() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "nao-e-um-jwt"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Sem vazar o token apresentado na mensagem de erro.
        String corpo = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(corpo.contains("nao-e-um-jwt"),
                "a resposta de erro não pode ecoar o token apresentado");
    }
}
