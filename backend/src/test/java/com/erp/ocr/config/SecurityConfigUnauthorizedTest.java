package com.erp.ocr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A distinção que o cliente depende de: <strong>não autenticado é 401,
 * autenticado sem permissão é 403</strong>.
 * <p>
 * O caso do 403 é o que quebrava o frontend: {@code apiService} só refaz a
 * sessão em resposta a 401. Com token expirado respondendo 403, o refresh
 * silencioso nunca disparava e o usuário perdia a sessão em vez de renovar
 * sozinho.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("401 para não autenticado, 403 para autenticado sem permissão")
class SecurityConfigUnauthorizedTest {

    @MockitoBean
    private GeminiOcrService geminiOcrService;

    @MockitoBean
    private AzureOcrService azureOcrService;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrganizationRepository organizacaoRepository;
    @Autowired
    private UserRepository usuarioRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private com.erp.ocr.service.TokenService tokenService;

    private String tokenDe(String email, Role role) {
        Organization org = organizacaoRepository.findByNome("Acme Supply Co.")
                .orElseGet(() -> organizacaoRepository.save(new Organization("Acme Supply Co.")));
        User usuario = usuarioRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setNome("User de Teste");
            u.setSenha(passwordEncoder.encode("senha-de-teste"));
            u.setRole(role);
            u.setOrganization(org);
            return usuarioRepository.save(u);
        });
        // O token e assinado com o segredo do profile de teste, o mesmo que o
        // SecurityFilter usa para validar.
        return tokenService.gerarToken(usuario);
    }

    private JsonNode corpo(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("sem token: 401 com corpo no formato ApiErrorDto")
    void semTokenRetorna401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/suppliers"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("token ausente é 401, não 403: o cliente renova a sessão a partir do 401")
                .isEqualTo(401);

        JsonNode json = corpo(result);
        assertThat(json.hasNonNull("message")).isTrue();
        assertThat(json.get("status").asInt()).isEqualTo(401);
        assertThat(json.hasNonNull("error")).isTrue();
        assertThat(json.hasNonNull("timestamp")).isTrue();
        assertThat(json.get("path").asText()).isEqualTo("/api/suppliers");
    }

    @Test
    @DisplayName("token inválido: 401 com corpo no formato ApiErrorDto")
    void tokenInvalidoRetorna401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/suppliers")
                .header("Authorization", "Bearer token.total.invalido"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("token inválido é 401: o frontend precisa do 401 para refazer a sessão")
                .isEqualTo(401);
        assertThat(corpo(result).get("status").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("token malformado (sem o esquema Bearer): 401")
    void tokenMalformadoRetorna401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/suppliers")
                .header("Authorization", "Basic dXNlcjpzZW5oYQ=="))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("token válido: a requisição passa")
    void tokenValidoPassa() throws Exception {
        String token = tokenDe("admin.401@teste.com", Role.ADMIN);

        MvcResult result = mockMvc.perform(get("/api/suppliers")
                .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a correção do 401 não pode fechar rota autenticada")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("autenticado sem permissão: 403, e continua 403")
    void autenticadoSemPermissaoRetorna403() throws Exception {
        String token = tokenDe("user.401@teste.com", Role.USER);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"novo@teste.com\",\"nome\":\"Novo\",\"senha\":\"SenhaForte123\",\"role\":\"USER\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("autenticado sem permissão é 403 — semântica diferente de não autenticado")
                .isEqualTo(403);
        assertThat(corpo(result).get("status").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("anônimo em rota de ADMIN: 401, não 403")
    void anonimoEmRotaDeAdminRetorna401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"novo@teste.com\",\"nome\":\"Novo\",\"senha\":\"SenhaForte123\",\"role\":\"USER\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("não autenticado é sempre 401, mesmo em rota que exige ADMIN")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("rota inexistente autenticada continua 404 — o 401 não engole o 404")
    void rotaInexistenteContinua404() throws Exception {
        String token = tokenDe("admin.404@teste.com", Role.ADMIN);

        MvcResult result = mockMvc.perform(get("/api/rota-que-nao-existe")
                .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("o novo entry point não pode transformar 404 em 401/403")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("método não suportado autenticado continua 405")
    void metodoNaoSuportadoContinua405() throws Exception {
        String token = tokenDe("admin.405@teste.com", Role.ADMIN);

        MvcResult result = mockMvc.perform(get("/api/auth/login")
                .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("o novo entry point não pode transformar 405 em 401/403")
                .isEqualTo(405);
    }
}
