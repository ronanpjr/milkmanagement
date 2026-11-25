package com.erp.ocr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4: prova que o Actuator sobe em uma porta separada e que a separacao e o
 * que sustenta o {@code permitAll} de /actuator/prometheus e /actuator/metrics.
 *
 * <p>O contrato tem duas partes:
 * <ul>
 *   <li>na porta de management: health, metrics e prometheus respondem 200 sem
 *       token; os endpoints que vazam configuracao (/env, /configprops, /beans)
 *       NAO respondem 200;</li>
 *   <li>na porta principal da API: /actuator/prometheus nao existe — se um dia
 *       as duas portas voltarem a ser a mesma, este teste quebra antes de as
 *       metricas ficarem expostas no Caddy/firewall da 8080.</li>
 * </ul>
 *
 * <p>Em producao a 8081 e publicada apenas em {@code 127.0.0.1}, o que
 * restringe o acesso a propria VM. Aqui a porta e aleatoria para o teste nao
 * depender de 8081 livre no runner.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
// Por padrao o Spring Boot desliga a observabilidade nos testes (economia de
// startup): sem isto o PrometheusMeterRegistry nao existe e o endpoint
// /actuator/prometheus nem e registrado, mascarando o que se quer provar.
@AutoConfigureObservability
class ActuatorManagementPortTest {

    private static final Set<HttpStatus> STATUS_DE_ACESSO_NEGADO = Set.of(
            HttpStatus.UNAUTHORIZED,
            HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND);

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    // O contexto de teste nao tem as credenciais de OCR (os segredos nao
    // existem no ambiente de teste); os providers sao mockados como nos
    // demais testes de contexto.
    @MockBean
    private AzureOcrService azureOcrService;

    @MockBean
    private GeminiOcrService geminiOcrService;

    @Test
    void healthRespondeSemTokenNaPortaDeManagement() {
        ResponseEntity<String> resposta = getManagement("/actuator/health");

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertNotNull(resposta.getBody());
        assertTrue(resposta.getBody().contains("\"status\":\"UP\""),
                "health deveria responder UP: " + resposta.getBody());
        // show-details=never: nada alem do status.
        assertFalse(resposta.getBody().contains("components"),
                "health nao pode vazar detalhes: " + resposta.getBody());
    }

    @Test
    void prometheusRespondeSemTokenNaPortaDeManagement() {
        ResponseEntity<String> resposta = getManagement("/actuator/prometheus");

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertNotNull(resposta.getBody());
        assertTrue(resposta.getBody().contains("# HELP"),
                "prometheus deveria devolver o formato de exposicao do Prometheus");
    }

    @Test
    void metricsRespondeSemTokenNaPortaDeManagement() {
        ResponseEntity<String> resposta = getManagement("/actuator/metrics");

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertNotNull(resposta.getBody());
        assertTrue(resposta.getBody().contains("\"names\""),
                "metrics deveria listar os nomes das metricas: " + resposta.getBody());
    }

    @Test
    void endpointsSensiveisNaoSaoPublicosNaPortaDeManagement() {
        for (String rota : new String[]{
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/mappings"}) {
            ResponseEntity<String> resposta = getManagement(rota);

            assertNotEquals(HttpStatus.OK, resposta.getStatusCode(),
                    rota + " nao pode ser publico");
            assertTrue(STATUS_DE_ACESSO_NEGADO.contains(resposta.getStatusCode())
                            || resposta.getStatusCode().is4xxClientError(),
                    rota + " deveria ser 401/403/404, veio " + resposta.getStatusCode());

            String corpo = resposta.getBody();
            if (corpo != null) {
                assertFalse(corpo.contains("propertySources"),
                        rota + " vazou properties");
                assertFalse(corpo.contains("systemEnvironment"),
                        rota + " vazou variavel de ambiente");
            }
        }
    }

    @Test
    void portaPrincipalNaoServePrometheus() {
        // A management esta em outra porta: a 8080 (porta da API, a que o
        // Caddy e os clientes antigos enxergam) nao deve servir metrica.
        ResponseEntity<String> resposta = restTemplate.getForEntity(
                "http://127.0.0.1:" + serverPort + "/actuator/prometheus", String.class);

        assertNotEquals(HttpStatus.OK, resposta.getStatusCode(),
                "a porta da API nao pode expor /actuator/prometheus");
        assertTrue(resposta.getStatusCode().is4xxClientError(),
                "esperado 4xx na porta da API, veio " + resposta.getStatusCode());
        assertTrue(managementPort != serverPort,
                "management e API precisam estar em portas distintas");
    }

    private ResponseEntity<String> getManagement(String path) {
        return restTemplate.getForEntity(
                "http://127.0.0.1:" + managementPort + path, String.class);
    }
}
