package com.erp.ocr.service.ocr;

import java.io.IOException;

/**
 * Fornece o token OAuth2 usado nas chamadas ao Vertex AI.
 * <p>
 * A interface existe para que o {@link GeminiOcrService} não dependa de ADC nos
 * testes: em produção o token vem da service account da VM, via metadata
 * server, e nos testes vem de uma implementação fake.
 */
@FunctionalInterface
public interface VertexAccessTokenProvider {

    /**
     * Devolve um access token válido. A implementação real renova o token
     * quando ele está expirado.
     */
    String accessToken() throws IOException;

    /**
     * Indica se as credenciais estão disponíveis. O default atende às
     * implementações de teste; a implementação de produção confere o ADC.
     */
    default boolean isConfigured() {
        return true;
    }
}
