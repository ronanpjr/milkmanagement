package com.erp.ocr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O health check do CD consulta /actuator/health sem cabecalho de
 * autenticacao. Se a rota cair no anyRequest().authenticated(), a resposta e
 * 403, o script nunca le "status":"UP" e o primeiro deploy reverte sozinho.
 *
 * Este teste existe para o 403 nao voltar em silencio: ele quebraria no CI,
 * nao no meio de um deploy em producao.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigActuatorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AzureOcrService azureOcrService;

    @MockBean
    private GeminiOcrService geminiOcrService;

    @Test
    void healthRespondeSemToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                // show-details=never: o CD so precisa de UP/DOWN, e nada mais
                // deve vazar para quem nao esta autenticado.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    void osDemaisEndpointsDoActuatorNaoSaoPublicos() throws Exception {
        // /actuator/env e /actuator/configproperties jogam variavel de
        // ambiente e properties (incluindo a do banco) para quem perguntar.
        //
        // O codigo exato aqui e 403 e nao 404: o SecurityFilter e um servlet
        // filter, entao ele nega ANTES do dispatcher poder concluir que a
        // rota nao existe. O que importa e a propriedade — nao responder 2xx
        // e nao devolver nada do conteudo.
        for (String rota : new String[]{
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/info",
                "/actuator/beans",
                "/actuator/mappings"}) {
            String corpo = mockMvc.perform(get(rota))
                    .andExpect(status().is4xxClientError())
                    .andReturn().getResponse().getContentAsString();

            org.junit.jupiter.api.Assertions.assertFalse(
                    corpo.contains("propertySources"), rota + " vazou properties");
            org.junit.jupiter.api.Assertions.assertFalse(
                    corpo.contains("systemEnvironment"), rota + " vazou variavel de ambiente");
        }
    }

    @Test
    void rotasDeApiContinuamExigindoAutenticacao() throws Exception {
        // A permissao do actuator nao pode ter arrastado outras rotas junto:
        // /api/** tem de continuar fechado para quem nao autentica. Este app
        // responde 403 (e nao 401) pela entrada do SecurityFilter, entao a
        // assercao e sobre o status de erro, nao sobre o codigo exato.
        mockMvc.perform(get("/api/suppliers"))
                .andExpect(status().is4xxClientError());
    }
}
