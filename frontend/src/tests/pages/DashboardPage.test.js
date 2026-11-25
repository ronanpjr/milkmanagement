import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import DashboardPage from '../../pages/DashboardPage';
import { apiService } from '../../services/apiService';

jest.mock('../../services/apiService');

// Recharts em jsdom não tem medida: o container responsivo renderiza largura
// zero e o gráfico não apareceria. Mantido o módulo real, só com o
// ResponsiveContainer fixo, como no teste de Relatórios.
jest.mock('recharts', () => {
    const OriginalModule = jest.requireActual('recharts');
    return {
        ...OriginalModule,
        ResponsiveContainer: ({ children }) => <div style={{ width: 500, height: 300 }}>{children}</div>,
    };
});

const HOJE = new Date();
const MES = HOJE.getMonth() + 1;
const ANO = HOJE.getFullYear();
const diaDoMes = (d) => `${ANO}-${String(d).padStart(2, '0')}`;

const card = (titulo) => screen.getByText(titulo).closest('.dashboard-card');

describe('DashboardPage', () => {
    let setNotification;
    let onNavigate;

    beforeEach(() => {
        localStorage.clear();
        setNotification = jest.fn();
        onNavigate = jest.fn();
        apiService.getAllProducersComAviso.mockResolvedValue([
            { id: 1, nome: 'Ana', ativo: true },
            { id: 2, nome: 'Bruno', ativo: true },
            { id: 3, nome: 'Carla', ativo: false },
        ]);
        apiService.getAllFechamentosComAviso.mockResolvedValue([]);
        // 1ª chamada: litros do mês corrente. 2ª chamada: série semestral.
        apiService.getAllColetasComAviso
            .mockResolvedValueOnce([
                { id: 1, data: diaDoMes(5), quantidadeLitros: 100, produtorId: 1 },
                { id: 2, data: diaDoMes(6), quantidadeLitros: 150, produtorId: 2 },
            ])
            .mockResolvedValue([]);
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('mostra aviso de carregamento antes de ter os dados', () => {
        apiService.getAllProducersComAviso.mockReturnValue(new Promise(() => {}));
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        expect(screen.getByText('Carregando dados do dashboard...')).toBeInTheDocument();
    });

    test('soma os litros do mês e conta apenas produtores ativos', async () => {
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(card('Produtores Ativos')).toHaveTextContent('2'); // Carla está inativa
        expect(card('Litros Coletados (Mês)')).toHaveTextContent('250 L'); // 100 + 150
    });

    test('soma apenas os fechamentos com pagamento pendente', async () => {
        apiService.getAllFechamentosComAviso.mockResolvedValue([
            { id: 1, produtorId: 1, statusPagamento: 'Pendente', totalLiquido: 1200.5 },
            { id: 2, produtorId: 2, statusPagamento: 'Pago', totalLiquido: 900 },
            { id: 3, produtorId: 3, statusPagamento: 'Pendente', totalLiquido: 99.5 },
        ]);
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        // 1200,50 + 99,50 = 1300,00 — o "Pago" não entra
        expect(card('Pagamentos Pendentes')).toHaveTextContent(/1\.300,00/);
    });

    test('conta como fechamento pendente o produtor ativo sem fechamento no mês anterior', async () => {
        const mesAnterior = MES === 1 ? 12 : MES - 1;
        const anoAnterior = MES === 1 ? ANO - 1 : ANO;
        apiService.getAllFechamentosComAviso.mockResolvedValue([
            { id: 1, produtorId: 1, mes: mesAnterior, ano: anoAnterior, statusPagamento: 'Pago', totalLiquido: 0 },
            // mesmo produtor, mas de outro mês: não conta como pago
            { id: 2, produtorId: 2, mes: (mesAnterior % 12) + 1, ano: anoAnterior, statusPagamento: 'Pago', totalLiquido: 0 },
        ]);
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(card('Fechamentos Pendentes (Mês)')).toHaveTextContent('1'); // só Bruno ficou de fora
    });

    test('cada card leva o usuário para a tela correspondente', async () => {
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);
        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

        fireEvent.click(card('Produtores Ativos'));
        fireEvent.click(card('Litros Coletados (Mês)'));
        fireEvent.click(card('Pagamentos Pendentes'));
        fireEvent.click(card('Fechamentos Pendentes (Mês)'));

        expect(onNavigate).toHaveBeenNthCalledWith(1, 'producers');
        expect(onNavigate).toHaveBeenNthCalledWith(2, 'collections');
        expect(onNavigate).toHaveBeenNthCalledWith(3, 'closings');
        expect(onNavigate).toHaveBeenNthCalledWith(4, 'closings');
    });

    test('não quebra quando o layout é usado sem função de navegação', async () => {
        render(<DashboardPage token="t" setNotification={setNotification} />);
        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

        expect(() => fireEvent.click(card('Produtores Ativos'))).not.toThrow();
    });

    test('falha de um fetch mostra banner de erro e não exibe 0 como se fosse dado real', async () => {
        const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
        apiService.getAllProducersComAviso.mockRejectedValue(new Error('produtores fora do ar'));
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        // Achado 19: "falhou" ≠ "sem dados" — banner aparece e o card não finge zero.
        expect(screen.getByRole('alert')).toHaveTextContent('produtores');
        expect(card('Produtores Ativos')).toHaveTextContent('—');
        // o resto que carregou continua aparecendo
        expect(card('Litros Coletados (Mês)')).toHaveTextContent('250 L');
        consoleError.mockRestore();
    });

    test('falha na série do gráfico não derruba os cards e sinaliza o gráfico', async () => {
        const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
        // a 2ª chamada (série do gráfico) é a que falha
        apiService.getAllColetasComAviso.mockReset();
        apiService.getAllColetasComAviso
            .mockResolvedValueOnce([{ id: 1, data: diaDoMes(5), quantidadeLitros: 100, produtorId: 1 }])
            .mockRejectedValue(new Error('série semestral indisponível'));
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(card('Litros Coletados (Mês)')).toHaveTextContent('100 L');
        expect(screen.getByText('Produção Semestral (Litros)')).toBeInTheDocument();
        expect(screen.getByText('Não foi possível carregar a série do gráfico.')).toBeInTheDocument();
        consoleError.mockRestore();
    });

    test('exceção síncrona de um fetch vira erro da seção, sem tela em branco', async () => {
        const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
        apiService.getAllProducersComAviso.mockImplementation(() => {
            throw new Error('apiService indisponível');
        });
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(screen.getByRole('alert')).toHaveTextContent('produtores');
        expect(screen.queryByText('Carregando dados do dashboard...')).not.toBeInTheDocument();
        consoleError.mockRestore();
    });

    test('avisa quando a paginação é truncada em vez de subestimar os totais em silêncio', async () => {
        // Achado 20: o wrapper chama o aviso; o Dashboard transforma em banner.
        apiService.getAllProducersComAviso.mockImplementation((token, aoTruncar) => {
            if (aoTruncar) aoTruncar(200 * 50);
            return Promise.resolve([{ id: 1, nome: 'Ana', ativo: true }]);
        });
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(screen.getByRole('status')).toHaveTextContent(/excedeu o limite de paginação/i);
    });

    test('registros com campos ausentes não quebram nem somam valores fantasma', async () => {
        // dado legado sem os campos novos não pode virar NaN na tela
        apiService.getAllProducersComAviso.mockResolvedValue([
            { id: 1, nome: 'Ana' }, // sem `ativo`
            { id: 2, nome: 'Bruno', ativo: true },
        ]);
        apiService.getAllFechamentosComAviso.mockResolvedValue([
            { id: 1, produtorId: 1, statusPagamento: 'Pendente' }, // sem `totalLiquido`
        ]);
        apiService.getAllColetasComAviso.mockReset();
        apiService.getAllColetasComAviso
            .mockResolvedValueOnce([
                { id: 1, data: diaDoMes(5), produtorId: 1 }, // sem `quantidadeLitros`
                { id: 2, data: diaDoMes(6), quantidadeLitros: 70, produtorId: 2 },
            ])
            .mockResolvedValue([]);

        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
        expect(card('Produtores Ativos')).toHaveTextContent('1');
        expect(card('Litros Coletados (Mês)')).toHaveTextContent('70 L');
        expect(card('Pagamentos Pendentes')).toHaveTextContent(/0,00/);
    });

    test('reaproveita o cache do navegador em vez de mostrar o carregamento', async () => {
        localStorage.setItem('dashboard_stats_cache', JSON.stringify({
            produtoresAtivos: 7,
            litrosColetadosMes: 4200,
            pagamentosPendentes: 1500,
            fechamentosPendentes: 3,
        }));
        localStorage.setItem('dashboard_chart_cache', JSON.stringify([{ name: 'Jan/26', Produção: 10 }]));

        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        // dados do cache na tela já no primeiro render, sem spinner
        expect(screen.queryByText('Carregando dados do dashboard...')).not.toBeInTheDocument();
        expect(card('Produtores Ativos')).toHaveTextContent('7');
        expect(card('Litros Coletados (Mês)')).toHaveTextContent('4.200 L');
        // e sinaliza que está atualizando em segundo plano
        expect(screen.getByText('Atualizando...')).toBeInTheDocument();

        await waitFor(() => expect(screen.queryByText('Atualizando...')).not.toBeInTheDocument());
        expect(card('Produtores Ativos')).toHaveTextContent('2'); // já com dado fresco
    });

    test('ignora cache corrompido no localStorage em vez de quebrar a tela', async () => {
        localStorage.setItem('dashboard_stats_cache', '{isto não é json');
        localStorage.setItem('dashboard_chart_cache', 'null');

        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        expect(screen.getByText('Carregando dados do dashboard...')).toBeInTheDocument();
        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
    });

    test('guarda o resultado em cache para a próxima abertura ser instantânea', async () => {
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);
        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

        expect(JSON.parse(localStorage.getItem('dashboard_stats_cache'))).toEqual({
            produtoresAtivos: 2,
            litrosColetadosMes: 250,
            pagamentosPendentes: 0,
            fechamentosPendentes: 2,
        });
        expect(JSON.parse(localStorage.getItem('dashboard_chart_cache'))).toHaveLength(6);
    });

    test('gráfico semestral: na virada de ano, os meses do ano anterior entram com o ano certo', async () => {
        // Regressão (issue #34): o balde de cada mês era rotulado com o ano
        // CORRENTE ("Dez./26" para dezembro do ano anterior). Com 6 meses
        // atravessando a virada do ano, as coletas de dezembro caíam fora do
        // gráfico — a chave do balde ("Dez./26") não batia com a chave
        // calculada a partir da data da coleta ("Dez./25") — e o total do
        // gráfico saía menor que o real, sem nenhum aviso.
        jest.useFakeTimers();
        jest.setSystemTime(new Date('2026-01-15T12:00:00'));
        try {
            apiService.getAllColetasComAviso.mockReset();
            apiService.getAllColetasComAviso
                .mockResolvedValueOnce([]) // litros do mês corrente
                .mockResolvedValue([
                    { id: 1, data: '2025-08-10', quantidadeLitros: 111, produtorId: 1 },
                    { id: 2, data: '2025-12-31', quantidadeLitros: 222, produtorId: 1 },
                    { id: 3, data: '2026-01-05', quantidadeLitros: 333, produtorId: 1 },
                ]);

            render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

            await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

            const chart = JSON.parse(localStorage.getItem('dashboard_chart_cache'));
            const porNome = chart.reduce((acc, c) => ({ ...acc, [c.name]: c.Produção }), {});
            expect(porNome['Ago./25']).toBe(111);
            expect(porNome['Dez./25']).toBe(222);
            expect(porNome['Jan./26']).toBe(333);
            // e nenhum balde com o ano errado
            expect(chart).toHaveLength(6);
            expect(chart.every(c => c.name.endsWith('/25') || c.name.endsWith('/26'))).toBe(true);
        } finally {
            jest.useRealTimers();
        }
    });

    test('os cards entram com a animação de entrada mesmo com cache', async () => {
        // A animação faz parte da tela — ela não é uma cortesia da primeira
        // visita. O que mudou (issue #35) foi o CUSTO dela: `opacity` e
        // `transform` (compostáveis), sem `opacity: 0` como estado estático
        // (que mantinha o card invisível durante o delay) e com duração
        // curta. Ver `src/index.css`.
        localStorage.setItem('dashboard_stats_cache', JSON.stringify({
            produtoresAtivos: 7,
            litrosColetadosMes: 4200,
            pagamentosPendentes: 1500,
            fechamentosPendentes: 3,
        }));
        localStorage.setItem('dashboard_chart_cache', JSON.stringify([{ name: 'Jan./26', Producao: 10 }]));

        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        // no primeiro render, sem esperar a rede
        expect(card('Produtores Ativos')).toHaveClass('anim-entrada');
        expect(card('Produtores Ativos')).toHaveTextContent('7');
    });

    test('na primeira carga, sem cache, os cards entram com a animação', async () => {
        render(<DashboardPage token="t" setNotification={setNotification} onNavigate={onNavigate} />);

        await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

        expect(card('Produtores Ativos')).toHaveClass('anim-entrada');
    });
});
