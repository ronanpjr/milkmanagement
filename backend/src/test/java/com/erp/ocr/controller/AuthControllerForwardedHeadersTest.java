package com.erp.ocr.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.LoginRateLimiterService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O rate limit de login (B7) e chaveado por IP. Com a porta 8080 ainda
 * publica, um cliente pode falar direto com a aplicacao e mandar um
 * X-Forwarded-For forjado a cada tentativa; se o controller confiar no header
 * cru, cada tentativa cai num bucket novo e o limite deixa de existir.
 *
 * A resolucao do IP real e responsabilidade do container
 * ({@code server.forward-headers-strategy=native} +
 * {@code server.tomcat.remoteip.internal-proxies}): o Tomcat so honra o
 * X-Forwarded-For quando a conexao vem de um proxy interno. Este teste fixa
 * que o controller usa {@code getRemoteAddr()}, nunca o header cru.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("IP do cliente para rate limit de login")
class AuthControllerForwardedHeadersTest {

    private static final String IP_DA_CONEXAO = "203.0.113.10";
    private static final String IP_FORJADO = "198.51.100.7";

    @MockitoBean
    private LoginRateLimiterService loginRateLimiter;

    @MockitoBean
    private GeminiOcrService geminiOcrService;

    @MockitoBean
    private AzureOcrService azureOcrService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("X-Forwarded-For forjado nao troca o bucket do rate limit")
    void xffForjadoNaoMudaOBucket() throws Exception {
        when(loginRateLimiter.isBlocked(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", IP_FORJADO)
                        .with(request -> {
                            request.setRemoteAddr(IP_DA_CONEXAO);
                            return request;
                        })
                        .content("{\"email\":\"nao-existe@rate-limit.test\",\"senha\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized());

        verify(loginRateLimiter).isBlocked(IP_DA_CONEXAO);
        verify(loginRateLimiter, never()).isBlocked(IP_FORJADO);
    }
}
