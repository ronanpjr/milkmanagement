package com.erp.ocr.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serializa todo {@code Long}/{@code long} como <strong>string</strong> no JSON.
 *
 * <h2>Por que</h2>
 *
 * Os ids são Snowflake (~1,22e18) e <strong>não cabem em um double do
 * JavaScript</strong> ({@code Number.MAX_SAFE_INTEGER} = 9,007e15):
 *
 * <pre>
 * Number("1220210112017039361")  -&gt; 1220210112017039400   // 39 dígitos perdidos
 * </pre>
 *
 * Com o id como número no JSON, o cliente perdia os dígitos ao parsear e
 * devolvia o valor <strong>arredondado</strong> como filtro {@code produtorId}
 * — e o servidor, que compara com o id exato, não encontrava nada. O efeito
 * observado era: a tela de Relatórios zerava (0 L, gráficos vazios) sempre que
 * o usuário selecionava um produtor, enquanto a lista de produtores (que vem
 * do mesmo payload) continuava aparecendo normalmente — um bug que parece de
 * frontend mas nasce na serialização.
 *
 * <h2>Como</h2>
 *
 * Como string, o id é transportado com todos os dígitos e o cliente o
 * devolve sem alteração. O contrato fica explícito: <strong>id é string
 * opaca, nunca número</strong> — o frontend trata id como texto e não faz
 * aritmética com ele.
 *
 * <p>Longs que não são id (ex.: {@code totalLitros} do {@code MonthlySummaryDto})
 * também saem como string. Nenhum Long-valued é usado em aritmética no
 * cliente hoje; se um dia houver, o campo deve virar {@code int}/{@code double}
 * em vez de voltar a ser número no JSON.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longComoStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
