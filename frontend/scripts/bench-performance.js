#!/usr/bin/env node
// scripts/bench-performance.js
//
// Benchmark de performance do frontend: mede, por tela, quanto tempo a main
// thread fica **bloqueada** (long tasks), quantas requisições HTTP a tela
// dispara e quanto tempo leva para a tela carregar.
//
// ## Por que
//
// "A página demora" e "a animação engasga" são, quase sempre, o mesmo
// sintoma: a main thread ocupada demais. O navegador só produz um quadro
// novo quando a main thread termina o que está fazendo — enquanto ela está
// bloqueada, a animação congela (é o "choppy").
//
// A medição de tempo de resposta do backend **não** revela isso: uma
// requisição que responde em 30 ms pode estar seguida de 1,5 s de JavaScript
// bloqueante no navegador, e o número do backend continua bonito.
//
// ## Como usar
//
//   npm run build                     # precisa do build de produção
//   npm run bench                     # mede e grava benchmarks/baseline.json
//   npm run bench -- --compare        # compara com o baseline gravado
//
// O script sobe o Chrome do sistema em modo headless, serve o build em uma
// porta local, faz login no backend e percorre as telas do menu.
//
// ## Requisitos
//
// - `google-chrome` (ou `chromium`) instalado e no PATH;
// - o backend no ar em `BENCH_API_URL` (padrão: http://localhost:8080);
// - as credenciais em `BENCH_EMAIL` / `BENCH_SENHA`.
//
// Nenhuma credencial é lida do repositório: vêm do ambiente.

const { spawn, spawnSync } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const RAIZ = path.resolve(__dirname, '..');
const BUILD = path.join(RAIZ, 'build');
const BASELINE = path.join(RAIZ, 'benchmarks', 'baseline.json');

const API = process.env.BENCH_API_URL || 'http://localhost:8080';
const EMAIL = process.env.BENCH_EMAIL;
const SENHA = process.env.BENCH_SENHA;
const PORTA_CDP = Number(process.env.BENCH_PORTA_CDP || 9333);
const CHROME = process.env.BENCH_CHROME
    || ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser']
        .find((bin) => spawnSync('which', [bin], { encoding: 'utf8' }).status === 0);

const COMPARAR = process.argv.includes('--compare');
const SALVAR = !COMPARAR;

const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

function falhar(mensagem, dica) {
    console.error(`\nERRO: ${mensagem}`);
    if (dica) console.error(`\n${dica}\n`);
    process.exit(1);
}

function httpGet(url) {
    return new Promise((resolve, reject) => {
        http.get(url, (res) => {
            let corpo = '';
            res.on('data', (c) => (corpo += c));
            res.on('end', () => {
                try { resolve(JSON.parse(corpo)); } catch (e) { reject(e); }
            });
        }).on('error', reject);
    });
}

// Qualquer resposta HTTP (inclusive 401/404/405) prova que o backend está
// no ar. Só "sem conexão" é erro.
function ping(url) {
    return new Promise((resolve) => {
        const req = http.get(url, (res) => { res.resume(); resolve(true); });
        req.on('error', () => resolve(false));
        req.setTimeout(4000, () => { req.destroy(); resolve(false); });
    });
}

async function esperarBackend() {
    const respostas = await Promise.all([
        ping(`${API}/actuator/health`),
        ping(`${API}/api/auth/login`),
    ]);
    if (!respostas.some(Boolean)) {
        falhar(`não consegui falar com o backend em ${API}`,
            'Suba o backend ou aponte BENCH_API_URL para o endereço certo.');
    }
}

// ---------------------------------------------------------------------------
// Cliente CDP (Chrome DevTools Protocol) por WebSocket
// ---------------------------------------------------------------------------

function abrirCdp() {
    const WebSocket = require('ws');
    const perfil = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-chrome-'));
    const chrome = spawn(CHROME, [
        '--headless=new',
        `--remote-debugging-port=${PORTA_CDP}`,
        '--no-sandbox',
        '--disable-gpu',
        '--window-size=1400,900',
        `--user-data-dir=${perfil}`,
        'about:blank',
    ], { stdio: 'ignore' });

    return new Promise((resolve, reject) => {
        let tentativas = 0;
        const tentar = async () => {
            try {
                const versao = await httpGet(`http://127.0.0.1:${PORTA_CDP}/json/version`);
                resolve({ chrome, ws: await conectar(versao.webSocketDebuggerUrl) });
            } catch (e) {
                if (++tentativas > 40) {
                    chrome.kill();
                    reject(new Error('o Chrome não abriu a porta de depuração'));
                } else {
                    setTimeout(tentar, 250);
                }
            }
        };
        tentar();
    });
}

function conectar(url) {
    const WebSocket = require('ws');
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 });
        ws.on('open', () => resolve(ws));
        ws.on('error', reject);
    });
}

// ---------------------------------------------------------------------------
// Telas medidas
// ---------------------------------------------------------------------------

const TELAS = [
    { nome: 'Dashboard', menu: 'Dashboard', espera: 4000, recarrega: true },
    { nome: 'Coletas', menu: 'Coletas', espera: 2500 },
    { nome: 'Fechamentos', menu: 'Fechamentos', espera: 2500 },
    { nome: 'Relatorios', menu: 'Relatórios', espera: 6000 },
    { nome: 'Produtores', menu: 'Produtores', espera: 2500 },
    { nome: 'Usuarios', menu: 'Usuários', espera: 2000 },
];

// ---------------------------------------------------------------------------

(async () => {
    if (!CHROME) {
        falhar('não encontrei o Chrome',
            'Instale o google-chrome ou aponte BENCH_CHROME para o executável.');
    }
    if (!EMAIL || !SENHA) {
        falhar('faltam as credenciais',
            'Defina BENCH_EMAIL e BENCH_SENHA no ambiente. (Ex.:\n' +
            '  BENCH_EMAIL=admin@exemplo.com BENCH_SENHA=... npm run bench)');
    }
    if (!fs.existsSync(path.join(BUILD, 'index.html'))) {
        falhar('não há build de produção',
            'Rode `npm run build` antes do benchmark. O que importa medir é o\n' +
            'bundle de produção, não o dev server.');
    }

    await esperarBackend();

    // O build e carregado por file://, exatamente como o app empacotado do
    // Electron faz. Alem de ser o cenario real de producao, evita depender
    // de uma porta livre: o backend ja libera a origem literal "null" que o
    // navegador envia para paginas de arquivo local.
    const app = `file://${path.join(BUILD, 'index.html')}`;

    console.log(`\nbackend: ${API}`);
    console.log(`app:     ${app} (build de produção)`);
    console.log(`chrome:  ${CHROME}\n`);

    const { chrome, ws } = await abrirCdp();

    let id = 0;
    const pendentes = new Map();
    const requisicoes = [];
    let sessionId = null;
    // Depois de uma navegacao, o contexto de execucao anterior morre: uma
    // `Runtime.evaluate` sem `contextId` pode acabar no contexto morto e ler
    // um array vazio (pareceria "nenhum long task"). Guardamos o mais
    // recente.
    let contextoAtual = null;

    ws.on('message', (raw) => {
        const msg = JSON.parse(raw);
        if (msg.id && pendentes.has(msg.id)) {
            const { resolve, reject } = pendentes.get(msg.id);
            pendentes.delete(msg.id);
            if (msg.error) reject(new Error(JSON.stringify(msg.error)));
            else resolve(msg.result);
        } else if (msg.method === 'Network.requestWillBeSent' && msg.sessionId === sessionId) {
            requisicoes.push(msg.params.request.url);
        } else if (msg.method === 'Runtime.executionContextCreated' && msg.sessionId === sessionId) {
            contextoAtual = msg.params.context.id;
        } else if (msg.method === 'Runtime.executionContextsCleared') {
            contextoAtual = null;
        }
    });

    // Com `flatten: true`, as mensagens de sessão precisam do sessionId; as
    // de browser-level (Target.*) NÃO podem ter a propriedade — o Chrome
    // rejeita com "Message may have string 'sessionId' property".
    const enviar = (method, params = {}, sid = sessionId) => new Promise((resolve, reject) => {
        const mid = ++id;
        pendentes.set(mid, { resolve, reject });
        const msg = { id: mid, method, params };
        if (typeof sid === 'string' && sid.length > 0) msg.sessionId = sid;
        ws.send(JSON.stringify(msg));
    });

    const avaliar = async (expressao) => {
        const params = {
            expression: expressao, returnByValue: true, awaitPromise: true,
        };
        if (contextoAtual) params.contextId = contextoAtual;
        const r = await enviar('Runtime.evaluate', params);
        if (r.exceptionDetails) {
            throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
        }
        return r.result.value;
    };

    const esperarPor = async (expressao, timeout = 40000) => {
        const t0 = Date.now();
        while (Date.now() - t0 < timeout) {
            try { if (await avaliar(expressao)) return true; } catch (e) { /* recarregando */ }
            await dormir(200);
        }
        return false;
    };

    try {
        const { targetId } = await enviar('Target.createTarget', { url: 'about:blank' }, null);
        ({ sessionId } = await enviar('Target.attachToTarget', { targetId, flatten: true }, null));

        await enviar('Page.enable');
        await enviar('Runtime.enable');
        await enviar('Network.enable');

        // medidor instalado ANTES de qualquer script da página: é o que
        // captura o long task do arranque, não o de depois.
        await enviar('Page.addScriptToEvaluateOnNewDocument', {
            source: `
                window.__longTasks = [];
                try {
                    new PerformanceObserver((lista) => {
                        lista.getEntries().forEach((e) => {
                            window.__longTasks.push({ dur: Math.round(e.duration), name: e.name });
                        });
                    }).observe({ entryTypes: ['longtask'] });
                } catch (e) { window.__longTaskSuportado = false; }
            `,
        });

        // --- login ---
        await enviar('Page.navigate', { url: app });
        const achouLogin = await esperarPor(`!!document.querySelector('#email')`);
        if (!achouLogin) falhar('a tela de login não apareceu', 'O build servido está incompleto?');

        await avaliar(`
            (() => {
                const set = (el, v) => {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, v);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                };
                set(document.querySelector('#email'), ${JSON.stringify(EMAIL)});
                set(document.querySelector('#password'), ${JSON.stringify(SENHA)});
                document.querySelector('form').requestSubmit();
                return true;
            })()
        `);

        const logou = await esperarPor(`!!document.querySelector('nav')`);
        if (!logou) {
            const estado = await avaliar(`({
                url: location.href,
                temLogin: !!document.querySelector('#email'),
                temNav: !!document.querySelector('nav'),
                h1: document.querySelector('h1') ? document.querySelector('h1').textContent : null,
                texto: document.body.innerText.slice(0, 300)
            })`);
            const texto = String(estado.texto || '');
            const dicaCORS = texto.includes('Não foi possível conectar')
                ? '\nO navegador não conseguiu falar com a API. Verifique se o backend está no ar ' +
                  `em ${API} e se a URL da API embutida no build (REACT_APP_API_BASE_URL) é essa.`
                : '';
            falhar('o login não passou',
                `Confira BENCH_EMAIL / BENCH_SENHA.${dicaCORS}\nEstado da pagina: ${JSON.stringify(estado, null, 2)}`);
        }

        // --- medição por tela ---
        const resultados = [];
        for (const tela of TELAS) {
            await avaliar('window.__longTasks = [];');
            requisicoes.length = 0;

            if (tela.recarrega) {
                // Clicar no item de menu da tela já ativa não remonta o
                // componente (setState com o mesmo valor não re-renderiza),
                // então a recarga é o que mede a entrada de verdade.
                await enviar('Page.navigate', { url: app });
                await esperarPor(`!!document.querySelector('nav')`);
            } else {
                const clicou = await avaliar(`(() => {
                    const b = [...document.querySelectorAll('nav button')]
                        .find((x) => x.textContent.includes(${JSON.stringify(tela.menu)}));
                    if (b) b.click();
                    return !!b;
                })()`);
                if (!clicou) {
                    console.warn(`  (aviso: item de menu "${tela.menu}" não encontrado)`);
                }
            }

            await dormir(tela.espera);

            const longTasks = JSON.parse(await avaliar('JSON.stringify(window.__longTasks || [])') || '[]');
            const bloqueante = longTasks.reduce((a, b) => a + b.dur, 0);
            const pior = longTasks.reduce((a, b) => Math.max(a, b.dur), 0);

            resultados.push({
                tela: tela.nome,
                requests: requisicoes.filter((u) => u.includes('/api/')).length,
                longTasks: longTasks.length,
                bloqueanteMs: bloqueante,
                piorTaskMs: pior,
            });
        }

        // --- relatório ---
        console.log('tela            reqs  longTasks  bloqueante  piorTask');
        console.log('-------------------------------------------------');
        for (const r of resultados) {
            console.log(
                r.tela.padEnd(15) +
                String(r.requests).padStart(5) +
                String(r.longTasks).padStart(11) +
                (r.bloqueanteMs + 'ms').padStart(12) +
                (r.piorTaskMs + 'ms').padStart(10)
            );
        }
        console.log('');

        if (COMPARAR) {
            if (!fs.existsSync(BASELINE)) {
                falhar('não há baseline para comparar',
                    `Rode \`npm run bench\` uma vez para gerar ${path.relative(RAIZ, BASELINE)}.`);
            }
            const base = JSON.parse(fs.readFileSync(BASELINE, 'utf8'));
            console.log('comparação com o baseline (negativo = melhor):');
            console.log('-------------------------------------------------');
            let pior = 0;
            for (const r of resultados) {
                const b = (base.telas || []).find((x) => x.tela === r.tela);
                if (!b) continue;
                const d = r.bloqueanteMs - b.bloqueanteMs;
                pior = Math.max(pior, d);
                const sinal = d > 0 ? '+' : '';
                console.log(
                    r.tela.padEnd(15) +
                    `${b.bloqueanteMs}ms -> ${r.bloqueanteMs}ms`.padEnd(24) +
                    `(${sinal}${d}ms)`
                );
            }
            console.log('');
            if (pior > 100) {
                console.log(`REGRESSÃO: +${pior}ms de tempo bloqueante.`);
                process.exitCode = 1;
            } else {
                console.log('Sem regressão relevante.');
            }
        }

        if (SALVAR) {
            fs.mkdirSync(path.dirname(BASELINE), { recursive: true });
            fs.writeFileSync(BASELINE, JSON.stringify({
                geradoEm: new Date().toISOString(),
                ambiente: {
                    node: process.version,
                    chrome: CHROME,
                    backend: API,
                    plataforma: `${os.platform()} ${os.release()}`,
                },
                telas: resultados,
            }, null, 2));
            console.log(`baseline gravado em ${path.relative(RAIZ, BASELINE)}`);
        }
    } finally {
        try { await enviar('Browser.close'); } catch (e) { /* já fechado */ }
        chrome.kill();
    }
})().catch((e) => {
    console.error(`\nERRO: ${e.message}\n`);
    process.exit(1);
});
