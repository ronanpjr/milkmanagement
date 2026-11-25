package com.erp.ocr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre CORS do {@link SecurityConfig} e o acesso à rota {@code /error}.
 * <p>
 * O app Electron empacotado carrega a página via {@code file://}; o navegador
 * envia {@code Origin: null} nesse caso, não {@code file://}. Sem a origem
 * literal {@code null} o preflight era rejeitado e o app não falava com a API.
 * <p>
 * A rota {@code /error} precisa ser {@code permitAll}: exigir {@code ROLE_ADMIN}
 * fazia um USER receber 403 no encaminhamento de erro, mascarando o status real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigCorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AzureOcrService azureOcrService;

    @MockBean
    private GeminiOcrService geminiOcrService;

    @Test
    void preflightComOrigemLiteralNullEDeixadoPassar() throws Exception {
        mockMvc.perform(options("/api/suppliers")
                        .header(HttpHeaders.ORIGIN, "null")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "null"));
    }

    @Test
    void preflightComOrigemFileTambemEDeixadoPassar() throws Exception {
        mockMvc.perform(options("/api/suppliers")
                        .header(HttpHeaders.ORIGIN, "file://")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "file://"));
    }

    @Test
    void preflightDeOrigemNaoPermitidaERejeitado() throws Exception {
        mockMvc.perform(options("/api/suppliers")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rotaDeErroNaoMascaraOStatusParaUmUser() throws Exception {
        mockMvc.perform(get("/error").with(user("usuario").roles("USER")))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void rotaInexistenteResponde404NoCorpoPadrao() throws Exception {
        mockMvc.perform(get("/api/rota-que-nao-existe").with(user("usuario").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/rota-que-nao-existe"));
    }
}
