// src/services/apiService.js
//
// API base URL: no hardcoded production IP ships in source. First non-empty
// wins:
//   1. window.__ERP_API_BASE_URL__ — injected at runtime by the
//      Electron preload (public/preload.js / public/electron.js), which lets
//      a packaged desktop build point at a different backend without a rebuild.
//   2. <meta name="erp-api-base-url"> — the URL baked into index.html at build
//      time. This is the path that works in the packaged Electron renderer.
//   3. REACT_APP_API_BASE_URL — CRA replaces this expression at build time;
//      kept for tests/dev when the meta tag is absent.
//   4. http://localhost:8080 — local-dev fallback only.
//
// Nao use `typeof process !== 'undefined'` como condicao para a URL de build:
// no renderer empacotado (nodeIntegration false) `process` nao existe, e o
// guard fazia a URL embutida ser ignorada, caindo no localhost.
const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const REQUEST_TIMEOUT_MS = 15000;
// O backend limita (clampa) o tamanho de página em PaginationUtil.MAX_PAGE_SIZE
// (= 200). Pedir size=1000 não falha: o servidor devolve silenciosamente só os
// primeiros 200 itens, e a UI passa a exibir dados truncados sem nenhum aviso.
// Por isso toda tela que precisa do conjunto COMPLETO (dropdowns de produtores,
// totais do dashboard, gráficos) precisa percorrer as páginas via fetchAllPages.
const SERVER_MAX_PAGE_SIZE = 200;
// Teto de segurança para o laço de paginação quando o servidor NÃO informa
// `totalPages`: evita laço infinito caso o backend comece a devolver
// `last`/`totalElements` incorretos.
const MAX_PAGINATION_PAGES = 50;
// Teto de volume quando o servidor INFORMA `totalPages`: 500 páginas x 200
// itens = 100.000 registros. Antes valia o teto de 50 páginas (10.000
// registros) mesmo com o servidor dizendo quantas páginas existem — e a
// lista truncava em silêncio, subestimando os totais do Dashboard e dos
// Relatórios (o relatório de 6 meses tem ~36 mil coletas = 183 páginas).
const MAX_PAGINATION_PAGES_COM_TOTAL = 500;
// Quantas páginas são buscadas ao mesmo tempo. O browser só abre ~6 conexões
// por host: acima disso as requisições ficam enfileiradas esperando socket, e
// o timeout de 15 s passa a valer para a fila (não só para a resposta) — o
// sintoma é "a requisição demorou demais" em carga, não lentidão.
const CONCURRENCIA_PAGINACAO = 6;
// Teto de requisições HTTP simultâneas no app inteiro (todas as chamadas
// autenticadas). Protege a tela de Relatórios, que abre uma busca por produtor
// selecionado: 40 produtores x 2 endpoints = 80 cadeias de paginação.
const LIMITE_REQUISICOES_SIMULTANEAS = 8;
// OCR batch upload calls Gemini/Azure per page and can take well over a
// minute for a multi-page PDF (each Gemini page call alone runs ~20-25s),
// so it needs a much longer budget than the default request timeout.
const OCR_UPLOAD_TIMEOUT_MS = 180000;

const getMetaApiBaseUrl = () => {
    if (typeof document === 'undefined') {
        return undefined;
    }
    const meta = document.querySelector('meta[name="erp-api-base-url"]');
    return meta?.content || undefined;
};

const getBuildTimeApiBaseUrl = () => {
    // Em producao o CRA substitui a expressao por uma string literal. Em
    // testes, process.env continua sendo lido em runtime.
    if (typeof process !== 'undefined') {
        return process.env?.REACT_APP_API_BASE_URL;
    }
    return undefined;
};

const getApiBaseUrl = () => {
    const runtimeWindowBaseUrl = typeof window !== 'undefined' ? window.__ERP_API_BASE_URL__ : undefined;
    const runtimeBaseUrl = runtimeWindowBaseUrl || getMetaApiBaseUrl() || getBuildTimeApiBaseUrl();
    return (runtimeBaseUrl || DEFAULT_API_BASE_URL).replace(/\/$/, '');
};

const buildUrl = (path) => `${getApiBaseUrl()}${path}`;

// --- Limitador de requisições simultâneas -------------------------------
//
// Fila FIFO que garante no máximo LIMITE_REQUISICOES_SIMULTANEAS requisições
// HTTP em voo. O slot é concedido ANTES de o timeout começar a contar: se o
// tempo de espera pela fila contasse para o timeout, uma tela que abre 80
// buscas em paralelo passaria a falhar com "demorou demais" — o sintoma
// errado para o que é, na verdade, apenas fila.
let requisicoesAtivas = 0;
const filaDeRequisicoes = [];

const comSlotDeRequisicao = async (tarefa) => {
    if (requisicoesAtivas >= LIMITE_REQUISICOES_SIMULTANEAS) {
        // Espera um slot. Quem libera o slot é quem acorda o próximo, e o
        // lugar na fila passa direto para ele (sem janela para outra
        // requisição roubar o slot).
        // eslint-disable-next-line no-await-in-loop
        await new Promise((resolve) => { filaDeRequisicoes.push(resolve); });
    } else {
        requisicoesAtivas += 1;
    }
    try {
        return await tarefa();
    } finally {
        requisicoesAtivas -= 1;
        const proximo = filaDeRequisicoes.shift();
        if (proximo) {
            requisicoesAtivas += 1;
            proximo();
        }
    }
};

const fetchComTimeout = async (url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) => {
    if (typeof AbortController === 'undefined') {
        return fetch(url, options);
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

    try {
        return await fetch(url, { ...options, signal: controller.signal });
    } catch (error) {
        if (error?.name === 'AbortError') {
            throw new Error('A requisição demorou demais para responder. Verifique sua conexão e tente novamente.');
        }

        if (error instanceof TypeError) {
            throw new Error('Não foi possível conectar ao servidor. Verifique a URL da API e a conectividade da rede.');
        }

        throw error;
    } finally {
        clearTimeout(timeoutId);
    }
};

const fetchWithTimeout = (url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) =>
    comSlotDeRequisicao(() => fetchComTimeout(url, options, timeoutMs));

/**
 * Traduz o header `Retry-After` (em segundos, conforme o contrato do backend)
 * numa frase curta em português. Não inventa precisão: arredonda para
 * minutos/horas. Devolve '' quando o valor é ausente ou inválido, para o
 * chamador poder anexar a frase só quando existe.
 */
export const formatRetryAfter = (retryAfterSeconds) => {
    const seconds = Number(retryAfterSeconds);
    if (!Number.isFinite(seconds) || seconds <= 0) {
        return '';
    }

    if (seconds < 60) {
        return 'Tente novamente em alguns segundos';
    }

    const minutes = Math.round(seconds / 60);
    if (minutes < 60) {
        return `Tente novamente em ${minutes} ${minutes === 1 ? 'minuto' : 'minutos'}`;
    }

    const hours = Math.round(seconds / 3600);
    return `Tente novamente em ${hours} ${hours === 1 ? 'hora' : 'horas'}`;
};

const handleResponse = async (response) => {
    // Se a resposta não for bem-sucedida (status 4xx ou 5xx),
    // sempre trate como um erro.
    if (!response.ok) {
        const errorBody = await response.text();

        // Prefere a mensagem estruturada do back-end (ApiErrorDto: { message, ... }).
        // Se o corpo não for JSON (ex: erro HTML de um proxy/servidor), cai para o
        // texto bruto, e por fim para um texto genérico com o status HTTP.
        let parsedMessage;
        try {
            const errorJson = JSON.parse(errorBody);
            parsedMessage = errorJson?.message;
        } catch {
            parsedMessage = undefined;
        }

        const error = new Error(parsedMessage || errorBody || `Erro ${response.status}`);
        // Metadados anexados para o chamador distinguir tipos de erro que não são
        // falha de rede nem de autenticação (ex.: cota diária de OCR → HTTP 429).
        // `headers` pode não existir em respostas mockadas/simplificadas.
        error.status = response.status;
        error.retryAfter = response.headers?.get?.('Retry-After') ?? null;
        if (parsedMessage !== undefined) {
            error.apiMessage = parsedMessage;
        }

        throw error;
    }

    // Se a resposta for bem-sucedida (status 2xx), verifique se há conteúdo.
    const contentType = response.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
        // Se for JSON, retorna o corpo da resposta em formato JSON.
        return response.json();
    }

    return null;
};

// --- Paginação transparente ----------------------------------------------
//
// Percorre todas as páginas de uma resposta `Page` do Spring e devolve o
// conteúdo agregado em um único array. `fetchPage(page)` deve devolver o
// objeto já parseado pelo handleResponse (ou null em caso de corpo vazio).
//
// Usa `last` (presente na serialização padrão do Page do Spring Data) como
// condição de término, com fallback para "página veio incompleta" caso um
// servidor alternative não exponha o campo.
//
// O teto de MAX_PAGINATION_PAGES existe para evitar laço infinito com backend
// inconsistente. Mas atingi-lo significa que a lista está TRUNCADA — e antes o
// código devolvia os 10.000 registros sem avisar, o que subestima os totais do
// dashboard e dos relatórios em silêncio, exatamente o problema que a
// paginação transparente veio para resolver. Por isso o consumo via
// `paginarTudoComAviso` avisa quando a truncagem aconteceu.
//
// Quando o servidor INFORMA `totalPages` (Spring Data sempre informa), a
// contagem deixa de ser adivinhada: buscamos exatamente as páginas que
// existem, em paralelo e com um teto de volume bem maior que 50 páginas.
// Antes, o relatório de 6 meses (~36 mil coletas = 183 páginas) era truncado
// em 10 mil registros sem aviso, e os totais saíam subestimados.
const fetchAllPages = async (fetchPage) => {
    const primeira = await fetchPage(0);
    const conteudoDaPrimeira = primeira && Array.isArray(primeira.content) ? primeira.content : [];

    const totalPaginas = Number.isInteger(primeira?.totalPages) && primeira.totalPages > 0
        ? primeira.totalPages
        : null;

    if (totalPaginas !== null) {
        const paginas = Math.min(totalPaginas, MAX_PAGINATION_PAGES_COM_TOTAL);
        const truncado = totalPaginas > MAX_PAGINATION_PAGES_COM_TOTAL;
        const itens = [...conteudoDaPrimeira];

        for (let inicio = 1; inicio < paginas; inicio += CONCURRENCIA_PAGINACAO) {
            const lote = [];
            for (let p = inicio; p < Math.min(inicio + CONCURRENCIA_PAGINACAO, paginas); p += 1) {
                lote.push(p);
            }
            // eslint-disable-next-line no-await-in-loop
            const respostas = await Promise.all(lote.map(p => fetchPage(p)));
            respostas.forEach((data) => {
                if (data && Array.isArray(data.content)) itens.push(...data.content);
            });
        }

        return { itens, truncado };
    }

    // Fallback: servidor sem `totalPages` (ou inconsistente). Segue página a
    // página, com o teto de segurança, até `last` (ou página incompleta).
    const isLastPage = (data, content) => data?.last === true
        || (data?.last === undefined && content.length < SERVER_MAX_PAGE_SIZE)
        || content.length === 0;

    if (isLastPage(primeira, conteudoDaPrimeira)) {
        return { itens: conteudoDaPrimeira, truncado: false };
    }

    let all = [...conteudoDaPrimeira];
    let truncado = false;
    let page = 1;

    while (page < MAX_PAGINATION_PAGES) {
        // eslint-disable-next-line no-await-in-loop
        const data = await fetchPage(page);
        const content = data && Array.isArray(data.content) ? data.content : [];

        all = all.concat(content);

        if (isLastPage(data, content)) break;

        page += 1;

        // Saiu do laço sem ter confirmado a última página.
        if (page === MAX_PAGINATION_PAGES) {
            truncado = true;
        }
    }

    return { itens: all, truncado };
};

/**
 * Igual a fetchAllPages, mas devolve só o array — para a maioria dos chamadores.
 * Use `paginarTudoComAviso` onde a truncagem precisa aparecer para o usuário.
 */
const paginarTudo = async (fetchPage) => (await fetchAllPages(fetchPage)).itens;

/**
 * Igual a `paginarTudo`, mas ACRESCENTA o aviso de truncagem: devolve o MESMO
 * array e chama `aoTruncar(totalItens)` quando a listagem bateu no teto de
 * `MAX_PAGINATION_PAGES`. O aviso é um callback porque cada tela decide como
 * exibi-lo (banner, notificação). Sem isso, os totais que somam a listagem
 * inteira capam em 10.000 registros em silêncio, exatamente o bug que motivou
 * a paginação transparente.
 */
export const paginarTudoComAviso = async (fetchPage, aoTruncar) => {
    const { itens, truncado } = await fetchAllPages(fetchPage);
    if (truncado && typeof aoTruncar === 'function') aoTruncar(itens.length);
    return itens;
};

// Monta a query string de paginação, reaproveitando os mesmos parâmetros que
// os métodos de página única já aceitam.
const buildPagedUrl = (path, { page, size, sortBy, sortDir, produtorId, inicio, fim }) => {
    let url = buildUrl(`${path}?page=${page}&size=${size}&sortBy=${sortBy}&sortDir=${sortDir}`);
    // encodeURIComponent: hoje os chamadores passam número e YYYY-MM-DD, então
    // não há exploração — mas um caractere especial quebraria a URL em silêncio.
    if (produtorId) url += `&produtorId=${encodeURIComponent(produtorId)}`;
    if (inicio) url += `&inicio=${encodeURIComponent(inicio)}`;
    if (fim) url += `&fim=${encodeURIComponent(fim)}`;
    return url;
};

// --- Sessão / refresh token ---------------------------------------------
//
// Os métodos autenticados abaixo continuam recebendo `token` explicitamente
// (o React continua sendo a fonte da verdade para o estado do token), mas
// toda chamada autenticada passa por `authorizedFetch`, que:
//   1. tenta a requisição com o access token atual;
//   2. em um 401, chama POST /api/auth/refresh uma única vez usando o
//      refresh token registrado via `setAuthHandlers`;
//   3. em caso de sucesso, repete a requisição original com o novo token e
//      avisa a aplicação (`onTokensRefreshed`) para persistir/atualizar o
//      estado React;
//   4. em caso de falha no refresh, limpa a sessão via `onSessionExpired`
//      (o App.js usa isso para acionar o handleLogout existente).
let authHandlers = {
    getRefreshToken: () => null,
    onTokensRefreshed: () => {},
    onSessionExpired: () => {},
};

export const setAuthHandlers = (handlers = {}) => {
    authHandlers = { ...authHandlers, ...handlers };
};

let refreshInFlight = null;

const refreshAccessToken = () => {
    if (refreshInFlight) {
        return refreshInFlight;
    }

    const currentRefreshToken = authHandlers.getRefreshToken();
    if (!currentRefreshToken) {
        return Promise.reject(new Error('Sessão expirada. Faça login novamente.'));
    }

    refreshInFlight = fetchWithTimeout(buildUrl('/api/auth/refresh'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: currentRefreshToken }),
    })
        .then(handleResponse)
        .then((data) => {
            if (!data?.token) {
                throw new Error('Resposta de refresh inválida.');
            }
            const nextRefreshToken = data.refreshToken || currentRefreshToken;
            authHandlers.onTokensRefreshed({ token: data.token, refreshToken: nextRefreshToken });
            return data.token;
        })
        .finally(() => {
            refreshInFlight = null;
        });

    return refreshInFlight;
};

// Executa um fetch autenticado, retentando uma única vez após um refresh
// bem-sucedido caso a resposta inicial seja 401.
const authorizedFetch = async (token, url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) => {
    const withAuthHeader = (accessToken) => ({
        ...options,
        headers: { ...(options.headers || {}), 'Authorization': `Bearer ${accessToken}` },
    });

    let response = await fetchWithTimeout(url, withAuthHeader(token), timeoutMs);

    if (response.status === 401) {
        try {
            const newToken = await refreshAccessToken();
            response = await fetchWithTimeout(url, withAuthHeader(newToken), timeoutMs);
        } catch (refreshError) {
            authHandlers.onSessionExpired();
            throw new Error('Sua sessão expirou. Faça login novamente.');
        }

        // Refresh deu certo mas a retentativa ainda deu 401: o token novo não
        // vale para este recurso (usuário desativado, permissão revogada).
        // Sem encerrar a sessão aqui, o app ficava "autenticado" com um token
        // que nunca funciona: cada chamada pagava um refresh extra e devolvia
        // erro, sem nunca deslogar.
        if (response.status === 401) {
            authHandlers.onSessionExpired();
            throw new Error('Sua sessão expirou. Faça login novamente.');
        }
    }

    return response;
};

export const apiService = {
    // Auth
    // Requer token de um usuário ADMIN autenticado — não há registro público.
    register: (token, nome, email, senha) => authorizedFetch(token, buildUrl('/api/auth/register'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nome, email, senha }),
    }).then(handleResponse),
    login: (email, senha) => fetchWithTimeout(buildUrl('/api/auth/login'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, senha }),
    }).then(handleResponse),

    /**
     * Perfil do usuário autenticado: { email, nome, role, organizacao }.
     *
     * O front NÃO pode assumir a role a partir do e-mail digitado no login nem
     * escondendo a tela de Usuários só na UI: quem é ADMIN decide o que o
     * backend aceita. A role real vem daqui.
     */
    getMe: (token) => authorizedFetch(token, buildUrl('/api/auth/me'))
        .then(handleResponse),

    // Produtores
    getProducers: (token, { page = 0, size = 20, sortBy = 'nome', sortDir = 'asc' } = {}) =>
        authorizedFetch(token, buildUrl(`/api/suppliers?page=${page}&size=${size}&sortBy=${sortBy}&sortDir=${sortDir}`))
            .then(handleResponse),
    // Lista COMPLETA de produtores (usada por dropdowns/seletores), percorrendo
    // todas as páginas. Usar `getProducers` com size alto NÃO serve: o backend
    // clampa em 200 e devolveria uma lista truncada.
    getAllProducers: (token) => paginarTudo((page) => authorizedFetch(
        token,
        buildUrl(`/api/suppliers?page=${page}&size=${SERVER_MAX_PAGE_SIZE}&sortBy=nome&sortDir=asc`)
    ).then(handleResponse)),
    /**
     * Igual a getAllProducers, mas avisa via `aoTruncar` quando a lista bateu
     * no teto de paginação e veio incompleta — para telas que contam os itens.
     */
    getAllProducersComAviso: (token, aoTruncar) => paginarTudoComAviso((page) => authorizedFetch(
        token,
        buildUrl(`/api/suppliers?page=${page}&size=${SERVER_MAX_PAGE_SIZE}&sortBy=nome&sortDir=asc`)
    ).then(handleResponse), aoTruncar),
    createProducer: (token, producerData) => authorizedFetch(token, buildUrl('/api/suppliers'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(producerData),
    }).then(handleResponse),
    updateProducer: (token, id, producerData) => authorizedFetch(token, buildUrl(`/api/suppliers/${id}`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(producerData),
    }).then(handleResponse),
    deleteProducer: (token, id) => authorizedFetch(token, buildUrl(`/api/suppliers/${id}`), {
        method: 'DELETE',
    }).then(handleResponse),

    /**
     * Igual a getAllColetas, mas avisa via `aoTruncar` quando a lista bateu no
     * teto de paginação e veio incompleta. Para telas que somam valores, o
     * silencioso truncava os totais sem nenhuma indicação.
     */
    getAllColetasComAviso: (token, { produtorId, inicio, fim } = {}, aoTruncar) => paginarTudoComAviso(
        (page) => authorizedFetch(
            token,
            buildPagedUrl('/api/collections', {
                page, size: SERVER_MAX_PAGE_SIZE, sortBy: 'data', sortDir: 'desc', produtorId, inicio, fim,
            })
        ).then(handleResponse),
        aoTruncar
    ),

    // Coletas
    getColetas: (token, { produtorId, inicio, fim, page = 0, size = 50, sortBy = 'data', sortDir = 'desc' } = {}) =>
        authorizedFetch(token, buildPagedUrl('/api/collections', { page, size, sortBy, sortDir, produtorId, inicio, fim }))
            .then(handleResponse),
    // Coletas de um produtor num período, TODAS as páginas combinadas.
    getAllColetas: (token, { produtorId, inicio, fim } = {}) => paginarTudo((page) => authorizedFetch(
        token,
        buildPagedUrl('/api/collections', {
            page, size: SERVER_MAX_PAGE_SIZE, sortBy: 'data', sortDir: 'desc', produtorId, inicio, fim,
        })
    ).then(handleResponse)),
    getColetasPorMes: (token, produtorId, ano, mes) => authorizedFetch(token, buildUrl(`/api/collections/supplier/${produtorId}/month/${ano}/${mes}`))
        .then(handleResponse).then(data => Array.isArray(data) ? data : []),
    // Busca todas as coletas de um produtor num período, paginando com um
    // tamanho de página limitado (alinhado ao teto do backend, BT-4) em vez
    // de pedir um `size` não-limitado de uma só vez.
    getColetasPorPeriodo: (token, produtorId, dataInicio, dataFim) =>
        apiService.getAllColetas(token, { produtorId, inicio: dataInicio, fim: dataFim }),
    uploadColetaBatch: (token, formData, ocrService = 'gemini', ano, mes) => {
        // B17: ano/mes explícitos permitem importar uma planilha antiga; sem
        // eles o backend assume "mês passado" (comportamento histórico).
        let url = buildUrl(`/api/collections/upload-batch?ocrService=${encodeURIComponent(ocrService)}`);
        if (ano && mes) url += `&ano=${encodeURIComponent(ano)}&mes=${encodeURIComponent(mes)}`;
        return authorizedFetch(token, url, {
            method: 'POST',
            body: formData, // Content-Type is multipart/form-data, let browser set it with boundary
        }, OCR_UPLOAD_TIMEOUT_MS).then(handleResponse);
    },
    salvarColetasEmLote: (token, coletas) => authorizedFetch(token, buildUrl('/api/collections/batch'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(coletas),
    }).then(handleResponse),

    // Fechamentos
    getFechamentos: (token, { produtorId, inicio, fim, page = 0, size = 20, sortBy = 'dataInicio', sortDir = 'desc' } = {}) =>
        authorizedFetch(token, buildPagedUrl('/api/settlements', { page, size, sortBy, sortDir, produtorId, inicio, fim }))
            .then(handleResponse),
    // Fechamentos, TODAS as páginas combinadas (relatórios que precisam do
    // histórico inteiro sem o corte silencioso de 200 do backend).
    getAllFechamentos: (token, { produtorId, inicio, fim } = {}) => paginarTudo((page) => authorizedFetch(
        token,
        buildPagedUrl('/api/settlements', {
            page, size: SERVER_MAX_PAGE_SIZE, sortBy: 'dataInicio', sortDir: 'desc', produtorId, inicio, fim,
        })
    ).then(handleResponse)),
    /**
     * Igual a getAllFechamentos, mas avisa via `aoTruncar` quando a lista bateu
     * no teto de paginação (para telas que somam/contam os fechamentos).
     */
    getAllFechamentosComAviso: (token, { produtorId, inicio, fim } = {}, aoTruncar) => paginarTudoComAviso((page) => authorizedFetch(
        token,
        buildPagedUrl('/api/settlements', {
            page, size: SERVER_MAX_PAGE_SIZE, sortBy: 'dataInicio', sortDir: 'desc', produtorId, inicio, fim,
        })
    ).then(handleResponse), aoTruncar),
    createFechamento: (token, data) => authorizedFetch(token, buildUrl('/api/settlements'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    }).then(handleResponse),
    atualizarPagamentoFechamento: (token, id, data) => authorizedFetch(token, buildUrl(`/api/settlements/${id}/payment`), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    }).then(handleResponse),
    deleteFechamento: (token, id) => authorizedFetch(token, buildUrl(`/api/settlements/${id}`), {
        method: 'DELETE',
    }).then(handleResponse),
    getProdutoresSemFechamento: (token, inicio, fim) =>
        authorizedFetch(token, buildUrl(`/api/suppliers/unsettled?inicio=${inicio}&fim=${fim}`))
            .then(handleResponse),

    // Nota Fiscal
    gerarNotaFiscal: (token, request) => authorizedFetch(token, buildUrl('/api/invoicing/generate'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
    }).then(handleResponse),
    exportarNotaFiscalXlsx: async (token, request) => {
        const response = await authorizedFetch(token, buildUrl('/api/invoicing/export-xlsx'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request),
        });
        if (!response.ok) {
            throw new Error(`Erro ao exportar: ${response.status}`);
        }
        return response.blob();
    },
};
