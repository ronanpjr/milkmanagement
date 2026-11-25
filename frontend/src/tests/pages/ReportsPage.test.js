import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import ReportsPage from '../../pages/ReportsPage';
import { apiService } from '../../services/apiService';

// Mock dependencies
jest.mock('../../services/apiService');

// Mock Recharts to avoid complex SVG rendering issues in tests
jest.mock('recharts', () => {
    const OriginalModule = jest.requireActual('recharts');
    return {
        ...OriginalModule,
        ResponsiveContainer: ({ children }) => <div style={{ width: 500, height: 300 }}>{children}</div>,
    };
});

const mockProducers = [
    { id: 1, nome: 'Produtor X', linha: 'Sul' },
    { id: 2, nome: 'Produtor Y', linha: 'Norte' }
];

// Datas dentro do intervalo padrão de filtro (1º dia do mês atual até hoje),
// para que filteredData realmente inclua essas coletas na comparação.
const today = new Date();
const isoToday = today.toISOString().split('T')[0];
const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];

const mockColetas = [
    { id: 1, data: firstOfMonth, quantidadeLitros: 100, produtorId: 1 },
    { id: 2, data: isoToday, quantidadeLitros: 200, produtorId: 2 }
];

const mockFechamentos = [
    {
        id: 10,
        produtorId: 1,
        totalLiquido: 500.00,
        totalLitros: 100,
        mes: today.getMonth() + 1,
        ano: today.getFullYear(),
        dataInicio: firstOfMonth,
        // A dataFim é a âncora do filtro de fechamentos (achado 21). Hoje cai
        // dentro do intervalo padrão (1º do mês até hoje).
        dataFim: isoToday,
    }
];

// Dispara a mudança dos dois <input type="date"> de filtro (Data Início/Fim).
const setDateRange = (container, start, end) => {
    const dateInputs = container.querySelectorAll('input[type="date"]');
    fireEvent.change(dateInputs[0], { target: { value: start } });
    fireEvent.change(dateInputs[1], { target: { value: end } });
};

describe('ReportsPage Integration Tests', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        // A página usa os métodos `*All` (paginação transparente dentro do
        // apiService), que já resolvem para arrays.
        apiService.getAllProducers.mockResolvedValue(mockProducers);
        // A página usa as variantes `*ComAviso`, que avisam quando a lista
        // bateu no teto de paginação (lista truncada = totais subestimados).
        apiService.getAllColetasComAviso.mockImplementation((_token, _filtros, aoTruncar) =>
            Promise.resolve(mockColetas).then(itens => { if (typeof aoTruncar === 'function') aoTruncar(itens.length); return itens; }));
        apiService.getAllFechamentosComAviso.mockImplementation((_token, _filtros, aoTruncar) =>
            Promise.resolve(mockFechamentos).then(itens => { if (typeof aoTruncar === 'function') aoTruncar(itens.length); return itens; }));
        apiService.getAllColetas.mockResolvedValue(mockColetas);
        apiService.getAllFechamentos.mockResolvedValue(mockFechamentos);
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('should render and load report data', async () => {
        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        expect(screen.getByText(/Carregando dados/i)).toBeInTheDocument();

        await waitFor(() => {
            expect(apiService.getAllProducers).toHaveBeenCalled();
            expect(apiService.getAllColetasComAviso).toHaveBeenCalled();
        });

        // Check for Summary Cards
        expect(screen.getByText('Total de Litros no Período')).toBeInTheDocument();
        const totalCard = screen.getByText('Total de Litros no Período').closest('.card');
        expect(totalCard).toHaveTextContent('300 L'); // 100 + 200

        // Check Table
        expect(screen.getByText('Produtor X')).toBeInTheDocument();
        expect(screen.getAllByText('100 L').length).toBeGreaterThan(0);
    });

    test('D4: avisa quando a carga de coletas falha, em vez de mostrar vazio (D4)', async () => {
        // Regressão: o `.catch(() => [])` local resolvia a promise ANTES do
        // try/catch da página, deixando aquele catch como código morto. O
        // resultado era a tela com gráficos vazios e cards zerados, sem aviso
        // nenhum — indistinguível de "não há dados no período".
        apiService.getAllColetasComAviso.mockRejectedValue(new Error('falha de rede'));
        apiService.getAllColetas.mockRejectedValue(new Error('falha de rede'));

        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith(
                expect.objectContaining({ type: 'error' })
            );
        });

        const mensagem = setNotificationMock.mock.calls
            .map(c => c[0].message).join(' | ');
        expect(mensagem).toMatch(/Erro ao carregar dados/i);
    });

    test('should handle empty data gracefully', async () => {
        apiService.getAllColetasComAviso.mockResolvedValue([]);
        apiService.getAllColetas.mockResolvedValue([]);
        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            const totalCard = screen.getByText('Total de Litros no Período').closest('.card');
            expect(totalCard).toHaveTextContent('0 L');
        });
    });

    test('A22: usa inicio/fim como parâmetros da API em coletas E fechamentos', async () => {
        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(apiService.getAllFechamentosComAviso).toHaveBeenCalled();
        });

        const coletasArgs = apiService.getAllColetasComAviso.mock.calls[0][1];
        expect(coletasArgs).toEqual(expect.objectContaining({ inicio: expect.any(String), fim: expect.any(String) }));
        expect(coletasArgs.produtorId).toBeUndefined();

        // Antes os fechamentos eram baixados por inteiro (sem inicio/fim) e o
        // recorte do período só acontecia no client. Agora o período vai para o
        // servidor, como já acontecia com as coletas (achado 22).
        const fechamentosArgs = apiService.getAllFechamentosComAviso.mock.calls[0][1];
        expect(fechamentosArgs).toEqual(expect.objectContaining({ inicio: expect.any(String), fim: expect.any(String) }));
        expect(fechamentosArgs.produtorId).toBeUndefined();
    });

    test('A22: multi-produtor usa o produtorId do servidor (uma busca por produtor)', async () => {
        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(apiService.getAllColetasComAviso).toHaveBeenCalled();
        });

        fireEvent.click(screen.getByText('Selecione os produtores...'));
        // Cada checkbox é a opção de um produtor dentro do dropdown.
        fireEvent.click(screen.getAllByRole('checkbox')[0]);
        fireEvent.click(screen.getAllByRole('checkbox')[1]);

        await waitFor(() => {
            const produtorIds = apiService.getAllColetasComAviso.mock.calls.map(([, args]) => args.produtorId);
            expect(produtorIds).toEqual(expect.arrayContaining(['1', '2']));
        });

        // Toda chamada por produtor continua levando o período.
        apiService.getAllColetasComAviso.mock.calls
            .filter(([, args]) => args.produtorId)
            .forEach(([, args]) => {
                expect(args.inicio).toEqual(expect.any(String));
                expect(args.fim).toEqual(expect.any(String));
            });
    });

    test('id grande de produtor vai para a API sem perder dígitos (bug do arredondamento)', async () => {
        // Regressão do bug "selecionar produtor zera a tela": os ids são
        // Snowflake (~1,22e18) e não cabem em um número do JavaScript. O
        // cliente arredondava o id e devolvia o valor arredondado como filtro
        // `produtorId` — o servidor não encontrava nada e a tela mostrava
        // 0 L com gráficos vazios.
        const ID_SNOWFLAKE = '1220210112017039361';
        apiService.getAllProducers.mockResolvedValue([{ id: ID_SNOWFLAKE, nome: 'Produtor Grande', linha: 'Sul' }]);
        apiService.getAllColetasComAviso.mockResolvedValue([
            { id: 1, data: isoToday, quantidadeLitros: 100, produtorId: ID_SNOWFLAKE },
        ]);
        apiService.getAllFechamentosComAviso.mockResolvedValue([]);

        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        fireEvent.click(screen.getByText('Selecione os produtores...'));
        fireEvent.click(screen.getAllByRole('checkbox')[0]);

        // O id enviado é o id EXATO, dígito a dígito.
        await waitFor(() => {
            const ids = apiService.getAllColetasComAviso.mock.calls.map(([, filtros]) => filtros.produtorId);
            expect(ids).toContain(ID_SNOWFLAKE);
        });

        // E a tela não fica zerada.
        await waitFor(() => {
            expect(screen.getByText('Total de Litros no Período').closest('.card')).toHaveTextContent('100 L');
        });
    });

    test('a grade de KPIs não deixa espaço vazio no fim (10 cards em 5 colunas)', async () => {
        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => expect(apiService.getAllColetasComAviso).toHaveBeenCalled());

        const cardTotal = screen.getByText('Total de Litros no Período').closest('.card');
        const grade = cardTotal.parentElement;
        // 10 cards com 4 colunas deixavam 2 células vazias ao lado do
        // "Em haver"; com 5 colunas, são 2 linhas cheias.
        expect(grade.className).toContain('lg:grid-cols-5');
        expect(grade.querySelectorAll('.card')).toHaveLength(10);
    });

    test('os cards de resumo têm todos os valores, mesmo sem dados no período', async () => {
        // Regressão: o early-return de `summaryCards` devolvia só 4 chaves e
        // os outros 6 cards apareciam VAZIOS (sem "R$ 0,00").
        apiService.getAllColetasComAviso.mockResolvedValue([]);
        apiService.getAllFechamentosComAviso.mockResolvedValue([]);

        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(screen.getByText('Total de Litros no Período').closest('.card')).toHaveTextContent('0 L');
        });

        ['Valor Total do Leite', 'Preço Médio do Litro', 'Valor Pago no Fechamento', 'Em haver',
            'Média Diária', 'Mediana por Produtor', 'Quant. Litros Pagos', 'Adiantamentos Pagos']
            .forEach((rotulo) => {
                const card = screen.getByText(rotulo).closest('.card');
                expect(card.textContent.trim()).not.toBe('');
                expect(card.textContent).toMatch(/R\$|L/);
            });
    });

    test('os gráficos mostram tendência, dispersão e correlência com coeficiente', async () => {
        // Dois fechamentos com volume e preço crescentes: a correlação existe.
        apiService.getAllFechamentosComAviso.mockResolvedValue([
            { id: 10, produtorId: 1, totalLiquido: 250, totalLitros: 100, precoFinal: 2.50, mes: today.getMonth() + 1, ano: today.getFullYear(), dataInicio: firstOfMonth, dataFim: isoToday },
            { id: 11, produtorId: 2, totalLiquido: 600, totalLitros: 200, precoFinal: 3.00, mes: today.getMonth() + 1, ano: today.getFullYear(), dataInicio: firstOfMonth, dataFim: isoToday },
        ]);

        render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => expect(apiService.getAllColetasComAviso).toHaveBeenCalled());

        // Volatilidade: média e dispersão por semana (não mais uma barra por dia).
        expect(screen.getByText('Volatilidade da Coleta (Média e Dispersão por Semana)')).toBeInTheDocument();
        // Correlação: com coeficiente e linha de tendência.
        expect(screen.getByText(/r = /)).toBeInTheDocument();
        // Seletor de granularidade da série temporal.
        expect(screen.getByLabelText('Granularidade')).toBeInTheDocument();
    });

    test('A21: filtra fechamentos pela dataFim (ciclo que atravessa meses), não pelo dia 1º do mês', async () => {
        // Ciclo 20/06–20/07, ancorado (mes/ano) em julho e dataFim em 25/07.
        const fechamentoCiclo = {
            id: 99,
            produtorId: 1,
            totalLiquido: 1000,
            totalLitros: 170,
            descontos: 0,
            mes: 7,
            ano: 2026,
            dataInicio: '2026-06-20',
            dataFim: '2026-07-25',
        };
        // Coletas dentro do recorte para que os cards de resumo sejam calculados
        // (sem coletas no período a página faz early-return nos valores de fechamento).
        apiService.getAllColetasComAviso.mockResolvedValue([
            { id: 1, data: '2026-07-15', quantidadeLitros: 100, produtorId: 1 },
        ]);
        apiService.getAllColetas.mockResolvedValue([
            { id: 1, data: '2026-07-15', quantidadeLitros: 100, produtorId: 1 },
        ]);
        apiService.getAllFechamentosComAviso.mockResolvedValue([fechamentoCiclo]);
        apiService.getAllFechamentos.mockResolvedValue([fechamentoCiclo]);

        const { container } = render(<ReportsPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(apiService.getAllFechamentosComAviso).toHaveBeenCalled();
        });

        // Recorte que começa depois do dia 1º do mês da dataFim (10/07). A âncora
        // antiga (new Date(2026, 6, 1) = 01/07) caía fora do intervalo e
        // descartava o fechamento; ancorando na dataFim (25/07) ele entra.
        setDateRange(container, '2026-07-10', '2026-07-30');

        await waitFor(() => {
            const card = screen.getByText('Quant. Litros Pagos').closest('.card');
            expect(card).toHaveTextContent('170 L');
        });
    });
});
