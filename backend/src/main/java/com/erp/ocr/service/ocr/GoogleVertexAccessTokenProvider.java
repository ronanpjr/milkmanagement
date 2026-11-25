package com.erp.ocr.service.ocr;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.IOException;
import java.util.List;

/**
 * Obtém credenciais via Application Default Credentials (ADC).
 * <p>
 * Em produção a aplicação roda na VM do GCE com a service account
 * {@code sa-vm-app}; o Google Auth Library lê o token do metadata server. Não
 * existe chave JSON: é o mesmo princípio do Workload Identity Federation usado
 * no deploy. Localmente, o ADC pode vir de
 * {@code gcloud auth application-default login}.
 * <p>
 * A carga é preguiçosa de propósito. Se não houver ADC (por exemplo, num
 * ambiente de desenvolvimento sem login), a aplicação ainda sobe e o
 * {@code OcrServiceFactory} recusa o Gemini com 400, em vez de derrubar o boot.
 */
public class GoogleVertexAccessTokenProvider implements VertexAccessTokenProvider {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private GoogleCredentials credentials;
    private IOException credentialFailure;

    @Override
    public synchronized String accessToken() throws IOException {
        GoogleCredentials atuais = credentials();
        AccessToken token = atuais.getAccessToken();
        if (token == null || token.getTokenValue() == null || token.getExpirationTime() == null
                || token.getExpirationTime().toInstant().isBefore(java.time.Instant.now().plusSeconds(60))) {
            atuais.refresh();
            token = atuais.getAccessToken();
        }
        if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
            throw new IOException("O ADC do Google Cloud não devolveu um access token válido.");
        }
        return token.getTokenValue();
    }

    @Override
    public synchronized boolean isConfigured() {
        try {
            credentials();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private GoogleCredentials credentials() throws IOException {
        if (credentials != null) {
            return credentials;
        }
        if (credentialFailure != null) {
            throw credentialFailure;
        }
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of(CLOUD_PLATFORM_SCOPE));
            return credentials;
        } catch (IOException e) {
            credentialFailure = e;
            throw e;
        }
    }
}
