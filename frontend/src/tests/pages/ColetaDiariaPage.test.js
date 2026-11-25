import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ColetaDiariaPage, { toDateKeyUTC, isValidMonth } from '../../pages/ColetaDiariaPage';
import { apiService } from '../../services/apiService';

// Mock dependencies
jest.mock('../../services/apiService');

const mockProducers = [
    { id: 1, nome: 'Produtor A', linha: 'Linha 1' },
    { id: 2, nome: 'Produtor B', linha: 'Linha 1' }
];

const mockColetas = [
    { id: 101, data: '2024-05-01', quantidadeLitros: 100, produtorId: 1 },
    { id: 102, data: '2024-05-02', quantidadeLitros: 120, produtorId: 1 }
];

describe('ColetaDiariaPage Integration Tests', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        // Dropdown de produtores usa a lista completa (paginação transparente
        // no apiService), que já resolve para um array.
        apiService.getAllProducers.mockResolvedValue(mockProducers);
        apiService.getColetasPorMes.mockResolvedValue(mockColetas);
        apiService.salvarColetasEmLote.mockResolvedValue({});
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('D1: nao trava em "Carregando coletas..." quando a lista de produtores falha (D1)', async () => {
        // Regressão: se a carga de produtores falhasse, selectedProducerId
        // ficava '' e fetchCollections fazia `return` ANTES de setIsLoading(false),
        // nunca mais desligando o spinner. A tela ficava presa em "Carregando
        // coletas..." sem forma de se recuperar sem reiniciar o app.
        apiService.getAllProducers.mockRejectedValue(new Error('rede'));

        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(screen.queryByText(/Carregando coletas/i)).not.toBeInTheDocument();
        });
    });

    test('should render and load producers and collections', async () => {
        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => {
            expect(apiService.getAllProducers).toHaveBeenCalled();
            expect(screen.getByText('Produtor A')).toBeInTheDocument();
        });

        // Set Date to May 2024 to match mock data
        const monthInput = screen.getByLabelText('Mês');
        fireEvent.change(monthInput, { target: { value: '5' } });
        const yearInput = screen.getByLabelText('Ano');
        fireEvent.change(yearInput, { target: { value: '2024' } });

        // Check if collections are loaded (Wait for input with value 100)
        await waitFor(() => {
            expect(screen.getByDisplayValue('100')).toBeInTheDocument();
        });
    });

    test('should allow changing producer', async () => {
        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor A'));

        // Find the select element for producer (second select in the top bar)
        // We can find by label associated
        const producerSelect = screen.getByLabelText('Produtor'); // Assuming label text matches

        // Change to Produtor B
        userEvent.selectOptions(producerSelect, '2');

        await waitFor(() => {
            expect(producerSelect.value).toBe('2');
            expect(apiService.getColetasPorMes).toHaveBeenCalledWith('fake-token', '2', expect.any(Number), expect.any(Number));
        });
    });

    test('should save changes after debounce', async () => {
        jest.useFakeTimers();
        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        // A tela abre no mês/ano corrente; as coletas mockadas são de
        // maio/2024, então o período precisa ser selecionado para que a grade
        // de lançamento seja montada.
        fireEvent.change(screen.getByLabelText('Mês'), { target: { value: '5' } });
        fireEvent.change(screen.getByLabelText('Ano'), { target: { value: '2024' } });

        await waitFor(() => screen.getByDisplayValue('100'));

        const input = screen.getByDisplayValue('100');
        fireEvent.change(input, { target: { value: '150' } });

        // Status should be unsaved
        expect(screen.getByTitle(/Alterações pendentes/)).toBeInTheDocument();

        // Fast-forward debounce time (1500ms)
        jest.advanceTimersByTime(1500);

        await waitFor(() => {
            expect(apiService.salvarColetasEmLote).toHaveBeenCalled();
            expect(screen.getByTitle(/Salvo na nuvem/)).toBeInTheDocument();
        });

        jest.useRealTimers();
    });
});

describe('ColetaDiariaPage — chave de data UTC (achado 24)', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        apiService.getAllProducers.mockResolvedValue(mockProducers);
        apiService.getColetasPorMes.mockResolvedValue(mockColetas);
        apiService.salvarColetasEmLote.mockResolvedValue({});
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('toDateKeyUTC normaliza a string do backend para YYYY-MM-DD', () => {
        expect(toDateKeyUTC('2024-05-01')).toBe('2024-05-01');
        expect(toDateKeyUTC('2024-05-31')).toBe('2024-05-31');
        expect(toDateKeyUTC('2024-12-31')).toBe('2024-12-31');
    });

    test('toDateKeyUTC é estável para objetos Date construídos em UTC', () => {
        // O calendário monta os dias com Date.UTC; a chave gerada precisa ser o
        // mesmo 'YYYY-MM-DD' independente do fuso local da máquina.
        expect(toDateKeyUTC(new Date(Date.UTC(2024, 4, 1)))).toBe('2024-05-01');
        expect(toDateKeyUTC(new Date(Date.UTC(2024, 0, 1)))).toBe('2024-01-01');
    });

    test('toDateKeyUTC retorna null para valor inválido', () => {
        expect(toDateKeyUTC('')).toBeNull();
        expect(toDateKeyUTC('não-é-data')).toBeNull();
    });

    test('isValidMonth aceita 1..12 e rejeita vazio, 13 e NaN', () => {
        expect(isValidMonth(1)).toBe(true);
        expect(isValidMonth(12)).toBe(true);
        expect(isValidMonth(13)).toBe(false);
        expect(isValidMonth(0)).toBe(false);
        expect(isValidMonth(NaN)).toBe(false);
    });

    test('a chave da coleta bate com a célula do calendário', async () => {
        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        fireEvent.change(screen.getByLabelText('Mês'), { target: { value: '5' } });
        fireEvent.change(screen.getByLabelText('Ano'), { target: { value: '2024' } });

        // coleta mockada de 2024-05-01 deve cair na célula do dia 1
        await waitFor(() => expect(screen.getByDisplayValue('100')).toBeInTheDocument());
        expect(apiService.getColetasPorMes).toHaveBeenCalledWith('fake-token', 1, 2024, 5);
    });

    test('mês 13 não dispara fetch e mantém a grade vazia', async () => {
        render(<ColetaDiariaPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        // aguarda a carga inicial (mês corrente, válido)
        await waitFor(() => expect(apiService.getColetasPorMes).toHaveBeenCalled());
        apiService.getColetasPorMes.mockClear();

        fireEvent.change(screen.getByLabelText('Mês'), { target: { value: '13' } });

        // grade sem células de lançamento
        expect(screen.queryAllByPlaceholderText('Litros')).toHaveLength(0);

        // dá chance ao efeito de rodar e confirma que a query inválida não foi montada
        await new Promise((resolve) => setTimeout(resolve, 50));
        expect(apiService.getColetasPorMes).not.toHaveBeenCalled();
        expect(screen.queryByText(/Carregando coletas/i)).not.toBeInTheDocument();
    });
});
