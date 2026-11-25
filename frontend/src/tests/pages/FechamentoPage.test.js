import React from 'react';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FechamentoPage from '../../pages/FechamentoPage';
import { apiService } from '../../services/apiService';
import { calculateFinalPrice } from '../../utils/pricingLogic';

// Mock dependencies
jest.mock('../../services/apiService');
jest.mock('../../utils/pricingLogic', () => ({
    calculateFinalPrice: jest.fn()
}));

const mockUser = { token: 'fake-token' };

const mockProducers = [
    { id: 1, nome: 'Produtor Teste', linha: 'Linha 1', usaPagamentoPorQualidade: true },
    { id: 2, nome: 'Produtor Comum', linha: 'Linha 2', usaPagamentoPorQualidade: false }
];

const mockFechamentos = [
    {
        id: 10,
        produtorId: 1,
        produtorNome: 'Produtor Teste',
        dataInicio: '2024-05-01',
        dataFim: '2024-05-31',
        totalLitros: 1000,
        totalLiquido: 2500.00,
        precoLitro: 2.00,
        statusPagamento: 'Pendente',
        dataPagamento: null,
        estrategiaPreco: 'Qualidade'
    }
];

describe('FechamentoPage Integration Tests', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        // JSDOM does not implement scrollIntoView
        window.HTMLElement.prototype.scrollIntoView = jest.fn();
        // Dropdown de produtores usa a lista completa (paginação transparente
        // no apiService), que já resolve para um array.
        apiService.getAllProducers.mockResolvedValue(mockProducers);
        apiService.getFechamentos.mockResolvedValue({
            content: mockFechamentos,
            totalPages: 1,
            totalElements: 1
        });
        apiService.createFechamento.mockResolvedValue({});
        apiService.atualizarPagamentoFechamento.mockResolvedValue({});
        apiService.deleteFechamento.mockResolvedValue({});
        apiService.getColetasPorPeriodo.mockResolvedValue([]);
        apiService.getAllColetas.mockResolvedValue([]);
        apiService.getProdutoresSemFechamento.mockResolvedValue([]);

        // CRA roda com `resetMocks: true`, que apaga a implementação definida na
        // factory do jest.mock antes de cada teste. Sem redefinir aqui, o
        // calculateFinalPrice usado nos previews retorna undefined.
        calculateFinalPrice.mockReturnValue({
            vCBT: 0, vCCS: 0, vGordura: 0, vProteina: 0, vVolume: 0, vFidelidade: 0, totalBonus: 0, finalPrice: 2.50
        });

        // Mock window methods
        window.confirm = jest.fn(() => true);
        window.print = jest.fn();
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('should render and fetch data on mount', async () => {
        render(<FechamentoPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        // Verify initial loading or data fetch calls
        expect(apiService.getAllProducers).toHaveBeenCalledWith('fake-token');
        expect(apiService.getFechamentos).toHaveBeenCalledWith('fake-token', { page: 0, size: 20, sortBy: 'dataInicio', sortDir: 'desc' });

        // Wait for data to be displayed
        await waitFor(() => {
            expect(screen.getByText(/Produtor Teste/i)).toBeInTheDocument();
            expect(screen.getByText(/2\.500,00/)).toBeInTheDocument();
        });
    });

    test('should open modal when "Gerar Fechamento" is clicked', async () => {
        render(<FechamentoPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Teste'));

        const btn = screen.getByText('Gerar Fechamento');
        fireEvent.click(btn);

        expect(screen.getByText('Gerar Novo Fechamento')).toBeInTheDocument();
        expect(screen.getByText('Salvar')).toBeInTheDocument();
    });

    test('deve abrir o modal já com produtor e período vindos da aba de pendentes', async () => {
        apiService.getProdutoresSemFechamento.mockResolvedValue([
            { id: 2, nome: 'Produtor Comum', linha: 'Linha 2' }
        ]);
        render(<FechamentoPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Teste'));

        // Abre a aba "Produtores Pendentes"
        fireEvent.click(screen.getByText('Produtores Pendentes'));

        await waitFor(() => {
            expect(apiService.getProdutoresSemFechamento).toHaveBeenCalled();
        });

        // Há dois botões "Gerar Fechamento": o do cabeçalho e o de cada produtor
        // pendente. O que inicializa produtor/período é o da lista de pendentes.
        // A lista é assíncrona, então esperamos o segundo botão aparecer.
        await waitFor(() => {
            expect(screen.getAllByText('Gerar Fechamento').length).toBeGreaterThan(1);
        });
        const gerarButtons = screen.getAllByText('Gerar Fechamento');
        fireEvent.click(gerarButtons[gerarButtons.length - 1]);

        // O modal precisa abrir JÁ preenchido: produtor selecionado e período
        // do mês escolhido. Antes these valores eram montados e descartados
        // (o state era escrito mas nunca lido e a prop não era passada), então
        // o modal abria em branco.
        expect(screen.getByText('Gerar Novo Fechamento')).toBeInTheDocument();
        await waitFor(() => {
            expect(screen.getByDisplayValue('Produtor Comum')).toBeInTheDocument();
        });
    });

    test('should filter list by producer name', async () => {
        render(<FechamentoPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Teste'));

        const searchInput = screen.getByPlaceholderText('Buscar produtor...');
        userEvent.type(searchInput, 'Inexistente');

        // Should filter out the row
        await waitFor(() => {
            const rows = screen.queryAllByText('Produtor Teste');
            // Depending on how filtering works, it might hide it. 
            // Since 'Produtor Teste' does not contain 'Inexistente', it should disappear.
            // Note: queryAllByText might still find it if it's in the DOM but hidden, 
            // but here the code removes it from the array map.
            expect(screen.queryByText('Produtor Teste')).not.toBeInTheDocument();
        });
    });

    test('should call delete API when delete button is clicked', async () => {
        await act(async () => {
            render(<FechamentoPage apiService={apiService} token={mockUser.token} setNotification={setNotificationMock} />);
        });

        await waitFor(() => screen.getByText('Produtor Teste'));

        const deleteButtons = screen.getAllByTitle('Excluir'); // Assuming there is an icon or title
        // In the absence of accessible name, we might need traverse by testId or similar if added.
        // Assuming the trash icon button is the second button in the row or similar.
        // For now, let's assume we can find it. If not, I'll need to check the component code for how to target it.
        // Let's interact with the first delete button.
        const deleteBtn = deleteButtons[0];

        await act(async () => {
            fireEvent.click(deleteBtn);
        });

        expect(window.confirm).toHaveBeenCalled();
        expect(apiService.deleteFechamento).toHaveBeenCalledWith(mockUser.token, 10);

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith(expect.objectContaining({ type: 'success' }));
        });
    });

    test('should create a new closing with flexible dates and discounts', async () => {
        apiService.getColetas = jest.fn().mockResolvedValue({ content: [], totalElements: 0 });

        await act(async () => {
            render(<FechamentoPage apiService={apiService} token={mockUser.token} setNotification={setNotificationMock} />);
        });

        await waitFor(() => screen.getByText('Produtor Teste'));

        // Open Modal
        const btnGerar = screen.getByText('Gerar Fechamento');
        fireEvent.click(btnGerar);

        expect(screen.getByText('Gerar Novo Fechamento')).toBeInTheDocument();

        // Select Producer via search input
        const selectProducer = screen.getByPlaceholderText('Digite para buscar...');
        fireEvent.change(selectProducer, { target: { value: '1' } });

        // Set Dates — find by current default value (first day / last day of current month)
        const dateInputs = document.querySelectorAll('input[type="date"]');
        const inputInicio = dateInputs[0];
        const inputFim = dateInputs[1];

        await act(async () => {
            fireEvent.change(inputInicio, { target: { value: '2024-06-01' } });
            fireEvent.change(inputFim, { target: { value: '2024-06-30' } });
        });

        // Set Price
        const inputPreco = screen.getByPlaceholderText('Ex: 2.50');
        fireEvent.change(inputPreco, { target: { value: '2.50' } });

        // Add Discount
        const btnAddDesconto = screen.getByText('Adicionar');
        fireEvent.click(btnAddDesconto);

        const inputDescricao = screen.getByPlaceholderText('Descrição');
        const inputValor = screen.getByPlaceholderText('R$');

        fireEvent.change(inputDescricao, { target: { value: 'Energia' } });
        fireEvent.change(inputValor, { target: { value: '150.00' } });

        // Submit
        const btnSalvar = screen.getByText('Salvar');
        await act(async () => {
            fireEvent.click(btnSalvar);
        });

        // O id do produtor é TEXTO (ver src/utils/ids.js).
        expect(apiService.createFechamento).toHaveBeenCalledWith(mockUser.token, expect.objectContaining({
            produtorId: '1',
            dataInicio: '2024-06-01',
            dataFim: '2024-06-30',
            precoLitro: 2.50,
            listaDescontos: [
                { descricao: 'Energia', valor: '150.00' }
            ]
        }));

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith(expect.objectContaining({ type: 'success' }));
        });
    });

    describe('preview do modo criação (achado 16)', () => {
        // Abre o modal em modo criação com o produtor default (produtores[0],
        // que usaPagamentoPorQualidade=true) e espera os litros do período.
        const openCreateModal = async () => {
            await act(async () => {
                render(<FechamentoPage apiService={apiService} token={mockUser.token} setNotification={setNotificationMock} />);
            });
            await waitFor(() => screen.getByText('Produtor Teste'));
            fireEvent.click(screen.getByText('Gerar Fechamento'));
            expect(screen.getByText('Gerar Novo Fechamento')).toBeInTheDocument();
        };

        test('produtor de qualidade: bônus digitados chegam nas posições certas', async () => {
            apiService.getAllColetas.mockResolvedValue([
                { quantidadeLitros: 600 },
                { quantidadeLitros: 400 }
            ]);

            await openCreateModal();

            fireEvent.change(screen.getByPlaceholderText('Ex: 2.50'), { target: { value: '2.50' } });
            fireEvent.change(screen.getByLabelText('Volume'), { target: { value: '0.15' } });
            fireEvent.change(screen.getByLabelText('Fidelidade'), { target: { value: '0.10' } });

            // O preview "Valor Final" do modo criação só aparece com litros > 0.
            await waitFor(() => {
                expect(screen.getByText(/1\.000 L/)).toBeInTheDocument();
            });

            // Aridade correta: (priceBase, cbt, ccs, gordura, proteina, fidelidade, bonusVolume).
            await waitFor(() => {
                expect(calculateFinalPrice).toHaveBeenCalledWith(2.5, 0, 0, 0, 0, 0.1, 0.15);
            });

            // Nenhuma chamada pode voltar à aridade antiga de 9 argumentos, que
            // deslocava fidelidade para receber totalLitros (achado 16).
            calculateFinalPrice.mock.calls.forEach((args) => {
                expect(args).toHaveLength(7);
            });
        });

        test('bônus de volume vazio é enviado como null (não como 0 nem litros)', async () => {
            apiService.getAllColetas.mockResolvedValue([{ quantidadeLitros: 300 }]);

            await openCreateModal();

            fireEvent.change(screen.getByPlaceholderText('Ex: 2.50'), { target: { value: '2.00' } });

            await waitFor(() => {
                expect(screen.getByText(/300 L/)).toBeInTheDocument();
            });

            await waitFor(() => {
                expect(calculateFinalPrice).toHaveBeenCalled();
            });

            const comBonusNulo = calculateFinalPrice.mock.calls.filter((args) => args[6] === null);
            expect(comBonusNulo.length).toBeGreaterThan(0);
            calculateFinalPrice.mock.calls.forEach((args) => {
                expect(args).toHaveLength(7);
            });
        });
    });
});
