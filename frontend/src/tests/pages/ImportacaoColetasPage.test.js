import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ImportacaoColetasPage from '../../pages/ImportacaoColetasPage';
import { apiService, formatRetryAfter } from '../../services/apiService';

// Mock dependencies
jest.mock('../../services/apiService');

// Mock Dropzone to easily simulate drops
jest.mock('react-dropzone', () => ({
    useDropzone: ({ onDrop }) => ({
        getRootProps: () => ({
            role: 'button',
            onClick: () => {
                // Simulate file drop
                const file = new File(['dummy content'], 'test.pdf', { type: 'application/pdf' });
                onDrop([file]);
            }
        }),
        getInputProps: () => ({}),
        isDragActive: false
    })
}));

describe('ImportacaoColetasPage Integration Tests', () => {
    let setNotificationMock;

    beforeEach(() => {
        setNotificationMock = jest.fn();
        apiService.uploadColetaBatch.mockResolvedValue([
            { produtorId: 1, producerName: 'Produtor X', data: '2024-05-01', quantidadeLitros: 100 },
            { produtorId: 1, producerName: 'Produtor X', data: '2024-05-02', quantidadeLitros: 120 },
            { produtorId: 2, producerName: 'Produtor Y', data: '2024-05-01', quantidadeLitros: 200 }
        ]);
        apiService.salvarColetasEmLote.mockResolvedValue({});
        // Autocompletar de produtores usa a lista completa (paginação
        // transparente no apiService), que já resolve para um array.
        apiService.getAllProducers.mockResolvedValue([
            { id: 1, nome: 'Produtor X' },
            { id: 2, nome: 'Produtor Y' }
        ]);
        // O módulo apiService é auto-mocado; o helper real é usado pela página
        // para montar a mensagem de cota, então devolvemos a implementação real.
        formatRetryAfter.mockImplementation(
            jest.requireActual('../../services/apiService').formatRetryAfter
        );
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('should render and handle file upload simulation', async () => {
        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        expect(screen.getByText('Importar Coletas')).toBeInTheDocument();

        // Trigger upload via mocked Dropzone click. The dropzone root also has role
        // "button" (per the mock below), but so do the Gemini/Azure OCR toggles, so
        // target it by its unambiguous label text instead of screen.getByRole('button').
        const dropzone = screen.getByText('Arrastar e soltar arquivo aqui');
        fireEvent.click(dropzone);

        // Expect transition to Review
        await waitFor(() => {
            expect(apiService.uploadColetaBatch).toHaveBeenCalled();
            expect(screen.getByText('Revisar Dados Extraídos')).toBeInTheDocument();
        });

        // Check columns (Dates)
        expect(screen.getByText('1/5')).toBeInTheDocument(); // 2024-05-01 -> 1/5
        expect(screen.getByText('2/5')).toBeInTheDocument();

        // Check Rows (Producers) - name is now an editable input, not static text
        expect(screen.getByDisplayValue('Produtor X')).toBeInTheDocument();
        expect(screen.getByDisplayValue('Produtor Y')).toBeInTheDocument();

        // Check Values
        // Inputs are tricky to select by specific cell value without aria-labels or IDs
        // Just checking input existence and values
        const inputs = screen.getAllByRole('spinbutton'); // type="number"
        expect(inputs.length).toBeGreaterThan(0);
    });

    test('should send the selected reference period to the OCR upload', async () => {
        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        // A planilha só traz os dias das coletas; ano/mês vêm da tela. Sem
        // isso o backend assumiria "mês passado" e importaria datas erradas.
        fireEvent.change(screen.getByLabelText('Mês de referência das coletas'), { target: { value: '05' } });
        fireEvent.change(screen.getByLabelText('Ano de referência das coletas'), { target: { value: '2024' } });

        fireEvent.click(screen.getByText('Arrastar e soltar arquivo aqui'));

        await waitFor(() => {
            expect(apiService.uploadColetaBatch).toHaveBeenCalledWith(
                'fake-token',
                expect.any(FormData),
                'gemini',
                '2024',
                '05'
            );
        });
    });

    test('shows the OCR daily-quota message on a 429 and does not retry the upload', async () => {
        const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
        const quotaError = new Error('Cota diária de OCR atingida.');
        quotaError.status = 429;
        quotaError.retryAfter = '7200';
        apiService.uploadColetaBatch.mockRejectedValue(quotaError);

        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);
        fireEvent.click(screen.getByText('Arrastar e soltar arquivo aqui'));

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith({
                message: 'Cota diária de OCR atingida. Tente novamente em 2 horas.',
                type: 'error'
            });
        });
        // O upload não tem retry automático: uma tentativa, sem laço extra.
        expect(apiService.uploadColetaBatch).toHaveBeenCalledTimes(1);
        // Erro continua visível no console (não é engolido).
        expect(consoleErrorSpy).toHaveBeenCalled();
        // Segue em ERROR, de volta à tela de upload.
        expect(screen.getByText('Arrastar e soltar arquivo aqui')).toBeInTheDocument();

        consoleErrorSpy.mockRestore();
    });

    test('shows a generic error message for non-429 upload failures', async () => {
        const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
        const genericError = new Error('Falha qualquer');
        genericError.status = 500;
        apiService.uploadColetaBatch.mockRejectedValue(genericError);

        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);
        fireEvent.click(screen.getByText('Arrastar e soltar arquivo aqui'));

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith({
                message: 'Erro ao processar arquivo: Falha qualquer',
                type: 'error'
            });
        });
        expect(apiService.uploadColetaBatch).toHaveBeenCalledTimes(1);

        consoleErrorSpy.mockRestore();
    });

    test('should save batch', async () => {
        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);

        const dropzone = screen.getByText('Arrastar e soltar arquivo aqui');
        fireEvent.click(dropzone); // Upload

        await waitFor(() => screen.getByText('Revisar Dados Extraídos'));

        const saveBtn = screen.getByText('Salvar Lote');
        fireEvent.click(saveBtn);

        await waitFor(() => {
            expect(apiService.salvarColetasEmLote).toHaveBeenCalledWith('fake-token', expect.any(Array));
            expect(screen.getByText('Importação Concluída!')).toBeInTheDocument();
        });
    });

    describe('Producer autocomplete on unmatched rows', () => {
        beforeEach(() => {
            apiService.uploadColetaBatch.mockResolvedValue([
                { produtorId: 1, producerName: 'Produtor X', data: '2024-05-01', quantidadeLitros: 100 },
                { produtorId: null, matched: false, produtorNomeDetectado: 'Jose da Silvaa', data: '2024-05-03', quantidadeLitros: 50 }
            ]);
        });

        const uploadAndReview = async () => {
            render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);
            fireEvent.click(screen.getByText('Arrastar e soltar arquivo aqui'));
            await screen.findByText('Revisar Dados Extraídos');
        };

        test('renders an editable input and a pending badge for an unmatched row', async () => {
            await uploadAndReview();

            expect(screen.getByDisplayValue('Jose da Silvaa')).toBeInTheDocument();
            expect(screen.getByText('Pendente')).toBeInTheDocument();
        });

        test('selecting an existing suggestion resolves the row and saves with its id', async () => {
            await uploadAndReview();

            const input = screen.getByDisplayValue('Jose da Silvaa');
            fireEvent.change(input, { target: { value: 'Produtor Y' } });
            fireEvent.click(screen.getByText('Produtor Y'));

            expect(screen.queryByText('Pendente')).not.toBeInTheDocument();

            fireEvent.click(screen.getByText('Salvar Lote'));

            await waitFor(() => {
                expect(apiService.salvarColetasEmLote).toHaveBeenCalledWith(
                    'fake-token',
                    expect.arrayContaining([
                        expect.objectContaining({ produtorId: '2', data: '2024-05-03', quantidadeLitros: 50 })
                    ])
                );
            });
        });

        test('creating a new producer inline resolves the row and saves with the new id', async () => {
            apiService.createProducer.mockResolvedValue({ id: 99, nome: 'Jose da Silvaa' });
            await uploadAndReview();

            const input = screen.getByDisplayValue('Jose da Silvaa');
            fireEvent.focus(input);
            fireEvent.click(screen.getByText('Criar novo produtor: "Jose da Silvaa"'));

            await waitFor(() => {
                expect(screen.queryByText('Pendente')).not.toBeInTheDocument();
            });
            expect(apiService.createProducer).toHaveBeenCalledWith('fake-token', { nome: 'Jose da Silvaa' });

            fireEvent.click(screen.getByText('Salvar Lote'));

            await waitFor(() => {
                expect(apiService.salvarColetasEmLote).toHaveBeenCalledWith(
                    'fake-token',
                    expect.arrayContaining([
                        expect.objectContaining({ produtorId: '99', data: '2024-05-03', quantidadeLitros: 50 })
                    ])
                );
            });
        });

        test('shows an inline error and keeps the row unresolved when producer creation fails', async () => {
            apiService.createProducer.mockRejectedValue(new Error('Produtor já existe'));
            await uploadAndReview();

            const input = screen.getByDisplayValue('Jose da Silvaa');
            fireEvent.focus(input);
            fireEvent.click(screen.getByText('Criar novo produtor: "Jose da Silvaa"'));

            await waitFor(() => {
                expect(screen.getByText('Produtor já existe')).toBeInTheDocument();
            });
            expect(screen.getByText('Pendente')).toBeInTheDocument();

            fireEvent.click(screen.getByText('Salvar Lote'));

            await waitFor(() => {
                expect(apiService.salvarColetasEmLote).toHaveBeenCalledWith(
                    'fake-token',
                    expect.not.arrayContaining([expect.objectContaining({ data: '2024-05-03' })])
                );
            });
        });
    });

    test('blocks saving when two rows resolve to the same producer and date', async () => {
        apiService.uploadColetaBatch.mockResolvedValue([
            { produtorId: 1, producerName: 'Produtor X', data: '2024-05-01', quantidadeLitros: 100 },
            { produtorId: null, matched: false, produtorNomeDetectado: 'Produtor X (duplicado)', data: '2024-05-01', quantidadeLitros: 999 }
        ]);

        render(<ImportacaoColetasPage apiService={apiService} token="fake-token" setNotification={setNotificationMock} />);
        fireEvent.click(screen.getByText('Arrastar e soltar arquivo aqui'));
        await screen.findByText('Revisar Dados Extraídos');

        const input = screen.getByDisplayValue('Produtor X (duplicado)');
        fireEvent.change(input, { target: { value: 'Produtor X' } });
        fireEvent.click(screen.getByText('Produtor X'));

        fireEvent.click(screen.getByText('Salvar Lote'));

        await waitFor(() => {
            expect(setNotificationMock).toHaveBeenCalledWith(
                expect.objectContaining({ type: 'error' })
            );
        });
        expect(apiService.salvarColetasEmLote).not.toHaveBeenCalled();
    });
});
