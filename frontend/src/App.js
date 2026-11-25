// src/App.js
import React, { useState, useEffect, useCallback } from 'react';
import Notification from './components/Notification';
import MainLayout from './layouts/MainLayout';
import LoginPage from './pages/LoginPage';
import TitleBar from './components/TitleBar';
import { setAuthHandlers, apiService } from './services/apiService';

import ErrorBoundary from './components/ErrorBoundary';

// Persiste o access token e o refresh token juntos, sempre no mesmo storage
// (localStorage se "lembrar de mim", senão sessionStorage), e limpa o outro
// storage para evitar tokens obsoletos duplicados.
const persistTokens = (accessToken, refreshToken, remember) => {
    const targetStorage = remember ? localStorage : sessionStorage;
    const otherStorage = remember ? sessionStorage : localStorage;

    targetStorage.setItem('authToken', accessToken);
    if (refreshToken) {
        targetStorage.setItem('refreshToken', refreshToken);
    } else {
        targetStorage.removeItem('refreshToken');
    }
    otherStorage.removeItem('authToken');
    otherStorage.removeItem('refreshToken');
};

/**
 * Chaves de cache de negócio guardadas no localStorage pelas telas.
 *
 * IMPORTANTE: estes caches sobreviviam ao logout. `producers_cache_*` guarda a
 * lista de produtores com `chavePix` e `inscricaoEstadual`, e é reidificado no
 * mount da tela — ou seja, quem logasse depois na mesma estação via os dados do
 * usuário anterior por até 5 minutos, antes mesmo da primeira resposta da API.
 * Nenhuma tela de erro limpava esses caches. Por isso o logout apaga tudo aqui.
 *
 * A lista é explícita de propósito: um prefixo amplo demais (`_cache`) apagaria
 * cache de terceiros, e um item esquecido aqui voltaria a vazar dado.
 */
const BUSINESS_CACHE_KEYS = [
    'dashboard_stats_cache',
    'dashboard_chart_cache',
];

/** Prefixos dos caches versionados pelas telas (ex.: `producers_cache_v2`). */
const BUSINESS_CACHE_PREFIXES = ['producers_cache', 'fechamentos_cache'];

const clearBusinessCaches = () => {
    BUSINESS_CACHE_KEYS.forEach((key) => localStorage.removeItem(key));

    // Versionados (v2, v3...), então casa por prefixo.
    Object.keys(localStorage)
        .filter((key) => BUSINESS_CACHE_PREFIXES.some((prefix) => key.startsWith(prefix)))
        .forEach((key) => localStorage.removeItem(key));
};

const clearTokens = () => {
    localStorage.removeItem('authToken');
    localStorage.removeItem('refreshToken');
    sessionStorage.removeItem('authToken');
    sessionStorage.removeItem('refreshToken');
    clearBusinessCaches();
};

const getStoredRefreshToken = () => localStorage.getItem('refreshToken') || sessionStorage.getItem('refreshToken');

export default function App() {
    const [token, setToken] = useState(localStorage.getItem('authToken') || sessionStorage.getItem('authToken'));
    const [currentPage, setCurrentPage] = useState(token ? 'dashboard' : 'login');
    const [notification, setNotification] = useState({ message: '', type: '' });

    /**
     * Perfil real do usuário, vindo de GET /api/auth/me.
     *
     * Fica `null` enquanto não carrega. Importante: a UI esconde o que é de
     * ADMIN, mas quem decide o que é permitido é o backend — um USER que abrir
     * a rota na mão recebe 403/404 de qualquer forma. Aqui é só para não
     * oferecer um botão que vai falhar.
     */
    const [perfil, setPerfil] = useState(null);

    useEffect(() => {
        if (!token) {
            setPerfil(null);
            return undefined;
        }

        let cancelled = false;
        apiService.getMe(token)
            .then((p) => { if (!cancelled) setPerfil(p); })
            .catch(() => {
                // Não derruba a sessão por causa do perfil: o app continua
                // utilizável, apenas sem os elementos de ADMIN. O logout
                // real é tratado pelo refresh/401 no apiService.
                if (!cancelled) setPerfil(null);
            });

        return () => { cancelled = true; };
    }, [token]);

    const handleLoginSuccess = (newToken, refreshToken, rememberMe) => {
        setToken(newToken);
        persistTokens(newToken, refreshToken, rememberMe);
        setCurrentPage('dashboard');
    };

    const handleLogout = useCallback(() => {
        setToken(null);
        clearTokens();
        setCurrentPage('login');
        setNotification({ message: 'Você saiu do sistema.', type: 'success' });
    }, []);

    // Liga o apiService ao estado de sessão do React: em qualquer chamada
    // autenticada, um 401 dispara automaticamente uma tentativa de refresh
    // (usando o refresh token armazenado) e repete a requisição original.
    // Se o refresh falhar, a sessão é encerrada de forma limpa (sem tela
    // travada/erro 500) via handleLogout.
    useEffect(() => {
        setAuthHandlers({
            getRefreshToken: getStoredRefreshToken,
            onTokensRefreshed: ({ token: newAccessToken, refreshToken: newRefreshToken }) => {
                const remember = !!localStorage.getItem('authToken');
                persistTokens(newAccessToken, newRefreshToken, remember);
                setToken(newAccessToken);
            },
            onSessionExpired: () => {
                handleLogout();
                setNotification({ message: 'Sua sessão expirou. Faça login novamente.', type: 'error' });
            },
        });
    }, [handleLogout]);

    const handleNavigate = (page) => {
        setCurrentPage(page);
    };

    // useCallback: sem isso a referência de `onDismiss` mudava a cada render do
    // App, e o efeito do Notification (depende de [message, onDismiss]) era
    // reiniciado a cada render — o timer de 3 s nunca chegava ao fim e a
    // notificação podia ficar na tela indefinidamente.
    const handleDismissNotification = useCallback(() => setNotification({ message: '', type: '' }), []);

    return (
        <div>
            <TitleBar />
            <Notification message={notification.message} type={notification.type} onDismiss={handleDismissNotification} />
            {token ? (
                <ErrorBoundary>
                    <MainLayout
                        currentPage={currentPage}
                        onNavigate={handleNavigate}
                        onLogout={handleLogout}
                        token={token}
                        setNotification={setNotification}
                        perfil={perfil}
                    />
                </ErrorBoundary>
            ) : (
                <LoginPage onLoginSuccess={handleLoginSuccess} onNavigate={handleNavigate} />
            )}
        </div>
    );
}