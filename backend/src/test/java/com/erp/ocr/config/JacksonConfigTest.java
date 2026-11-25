package com.erp.ocr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O contrato de serialização de ids.
 *
 * <p>Os ids são Snowflake (~1,22e18) e não cabem em um {@code double} do
 * JavaScript ({@code Number.MAX_SAFE_INTEGER} = 9,007e15). Serializados como
 * número JSON, o cliente perde os dígitos ao parsear e devolve o valor
 * arredondado — e o servidor, que compara com o id exato, não encontra nada.
 *
 * <p>Este teste é a <strong>rede de paridade</strong> do contrato: se alguém
 * voltar a serializar {@code Long} como número, ele quebra aqui.
 */
class JacksonConfigTest {

    /** Id real do ambiente de produção, com 19 dígitos. */
    private static final Long ID_SNOWFLAKE = 1220210112017039361L;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void serializaLongComoStringPreservandoTodosOsDigitos() throws Exception {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new ComId(ID_SNOWFLAKE));

            // O número exato, dígito a dígito — sem os 39 dígitos que o
            // JavaScript perderia ao parsear.
            assertTrue(json.contains("\"1220210112017039361\""),
                    "O id deveria sair como string com todos os dígitos, mas veio: " + json);
            assertTrue(!json.contains("1220210112017039400"),
                    "O id não pode sair arredondado (é o que o JS faria): " + json);
        });
    }

    @Test
    void oIdVoltaDoJsonParaOValorExato() throws Exception {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new ComId(ID_SNOWFLAKE));
            ComId lido = mapper.readValue(json, ComId.class);

            // Ida e volta sem perder dígito: é o que garante que o filtro
            // `produtorId` enviado pelo cliente encontre o registro.
            assertEquals(ID_SNOWFLAKE, lido.id());
        });
    }

    @Test
    void oIdLongComumTambemSaiComoString() throws Exception {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new ComId(42L));

            assertTrue(json.contains("\"42\""), "Esperava o id como string, veio: " + json);
        });
    }

    /** Record de teste: um id em um record, como nos DTOs da API. */
    record ComId(Long id) {
    }
}
