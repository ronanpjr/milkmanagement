import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ProdutorPage from '../../pages/ProdutorPage';
import { apiService } from '../../services/apiService';

// Mock dependencies
jest.mock('../../services/apiService');

const mockProducers = [
    { id: 1, nome: 'Produtor Alpha', linha: 'Linha A', ativo: true, usaPagamentoPorQualidade: false },
    { id: 2, nome: 'Produtor Beta', linha: 'Linha B', ativo: false, usaPagamentoPorQualidade: true }
];

describe('ProdutorPage Integration Tests', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        // A listagem de produtores é paginada: a página lê `data.content`.
        apiService.getProducers.mockResolvedValue({
            content: mockProducers,
            totalPages: 1,
            totalElements: mockProducers.length
        });
        // A busca na tela usa a lista completa (getAllProducers), separada da
        // página paginada exibida na tabela.
        apiService.getAllProducers.mockResolvedValue(mockProducers);
        apiService.createProducer.mockResolvedValue({});
        apiService.updateProducer.mockResolvedValue({});
        apiService.deleteProducer.mockResolvedValue({});

        window.confirm = jest.fn(() => true);
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('should render and load producers', async () => {
        render(<ProdutorPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        expect(screen.getByText(/Carregando produtores/i)).toBeInTheDocument();

        await waitFor(() => {
            expect(screen.getByText('Produtor Alpha')).toBeInTheDocument();
            expect(screen.getByText('Produtor Beta')).toBeInTheDocument();
        });
    });

    test('should find a producer that is NOT on the current page (D3)', async () => {
        // Regressão: a busca e os filtros eram aplicados sobre a página
        // paginada corrente. Com 3 páginas, buscar o produtor da página 3
        // respondia "nenhum resultado" — ele era invisível para a busca.
        const paginated = [mockProducers[0]];
        const full = [
            mockProducers[0],
            mockProducers[1],
            { id: 3, nome: 'Produtor Gamma', linha: 'Linha C', ativo: true, usaPagamentoPorQualidade: false },
        ];
        apiService.getProducers.mockResolvedValue({
            content: paginated,
            totalPages: 3,
            totalElements: full.length
        });
        apiService.getAllProducers.mockResolvedValue(full);

        render(<ProdutorPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Alpha'));

        fireEvent.change(screen.getByPlaceholderText(/Buscar por nome ou linha/i), {
            target: { value: 'Gamma' }
        });

        // "Gamma" não está na página exibida; só aparece se a busca usar a
        // lista completa.
        expect(await screen.findByText('Produtor Gamma')).toBeInTheDocument();
        expect(screen.queryByText('Produtor Alpha')).not.toBeInTheDocument();
    });

    test('should open add modal and save new producer', async () => {
        render(<ProdutorPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Alpha'));

        const addBtn = screen.getByText('Adicionar');
        fireEvent.click(addBtn);

        expect(screen.getByText('Adicionar Produtor')).toBeInTheDocument();

        // Fill form
        // Note: The input has name="nome" but might not have a label linked via htmlFor, checking implementation
        // <label ...>Nome</label><input name="nome" ...>
        // Best practice to select by label, but generic input selection works too if unique.
        // Let's assume structure: <label>Nome</label> <input>

        // Using getAllByRole because there might be multiple text inputs
        // Or getByRole 'textbox', name: 'Nome' if properly labeled.
        // Let's try select by structure or just use container queries if needed.
        // Looking at ProdutorPage.js code: <label ...>Nome</label><input ...> - No htmlFor/id.
        // So we might need to rely on order or generic selectors.

        // Simpler approach for this environment:
        // const nameInput = screen.getByRole('textbox', {name: /Nome/i}) // Might fail if not properly associated
        // Let's fallback to container query if label not associated.

        // Wait, React Testing Library can find by label text if wrapped or associated.
        // Code: <div><label>Nome</label><input ...></div>. Not wrapped, no id.
        // So getByLabelText won't work.
        // We will select by closest approximation.

        const inputs = screen.getAllByRole('textbox');
        // Index 0 is search input, Index 1 is Name input in modal
        const nameInput = inputs[1];

        userEvent.type(nameInput, 'Produtor Gamma');

        const saveBtn = screen.getByText('Salvar');
        fireEvent.click(saveBtn);

        await waitFor(() => {
            expect(apiService.createProducer).toHaveBeenCalledWith('fake-token', expect.objectContaining({
                nome: 'Produtor Gamma'
            }));
        });
    });

    test('should filter producers', async () => {
        render(<ProdutorPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Alpha'));

        const searchInput = screen.getByPlaceholderText('Buscar por nome ou linha...');
        userEvent.type(searchInput, 'Alpha');

        await waitFor(() => {
            expect(screen.queryByText('Produtor Beta')).not.toBeInTheDocument();
            expect(screen.getByText('Produtor Alpha')).toBeInTheDocument();
        });
    });

    test('should delete producer', async () => {
        render(<ProdutorPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        await waitFor(() => screen.getByText('Produtor Alpha'));

        // Delete Produtor Alpha (first one)
        // Find delete button. Icon is TrashIcon.
        // The button has className "text-red-600".
        // Or we can get row and find button.

        const rows = screen.getAllByRole('row');
        // Row 0 is header. Row 1 is Produtor Alpha.
        const rowAlpha = rows[1];

        const deleteBtn = within(rowAlpha).getByTitle('Excluir');

        fireEvent.click(deleteBtn);

        expect(window.confirm).toHaveBeenCalled();
        expect(apiService.deleteProducer).toHaveBeenCalledWith('fake-token', 1);
    });
});

// Helper for within
import { within } from '@testing-library/dom';
