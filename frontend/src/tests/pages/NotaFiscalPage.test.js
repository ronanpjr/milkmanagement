import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import NotaFiscalPage from '../../pages/NotaFiscalPage';
import { apiService } from '../../services/apiService';

jest.mock('../../services/apiService');

const PRODUTORES = [
    { id: 1, nome: 'Ana Ribeiro', inscricaoEstadual: 'IE-111' },
    { id: 2, nome: 'Bruno Alves', inscricaoEstadual: null },
];

const REGISTROS = [
    { cdProdutorIe: 'IE-111', dtRecebimento: '2026-03-05', qtLitros: 120, cdPlaca: 'ABC-1D23' },
    { cdProdutorIe: 'IE-111', dtRecebimento: '2026-03-06', qtLitros: 80, cdPlaca: 'ABC-1D23' },
];

describe('NotaFiscalPage', () => {
    let setNotification;
    let consoleError;

    const seletorProdutor = () => screen.getByPlaceholderText('Selecione um produtor...');
    const botaoGerar = () => screen.getByRole('button', { name: /Gerar Dados|Gerando/ });

    const abrirSeletor = () => fireEvent.focus(seletorProdutor());

    const escolherProdutor = async (nome) => {
        abrirSeletor();
        fireEvent.click(await screen.findByText(nome));
        // deixa o blur pendente (setTimeout de 150ms) resolver antes de seguir
        await act(async () => { jest.advanceTimersByTime(200); });
    };

    beforeEach(() => {
        jest.useFakeTimers();
        setNotification = jest.fn();
        consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
        apiService.getAllProducers.mockResolvedValue(PRODUTORES);
        apiService.gerarNotaFiscal.mockResolvedValue(REGISTROS);
        apiService.exportarNotaFiscalXlsx.mockResolvedValue(new Blob(['xlsx']));
    });

    afterEach(() => {
        jest.clearAllTimers();
        jest.useRealTimers();
        consoleError.mockRestore();
        jest.clearAllMocks();
    });

    test('carrega a lista de produtores e abre o mês/ano corrente', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);

        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        expect(screen.getByText('Gerar Dados para Nota Fiscal')).toBeInTheDocument();
        expect(screen.getByText('Configurações')).toBeInTheDocument();

        const hoje = new Date();
        expect(screen.getByDisplayValue(String(hoje.getFullYear()))).toBeInTheDocument();
        expect(screen.getByRole('combobox')).toHaveValue(String(hoje.getMonth() + 1));
    });

    test('lista os produtores com a inscrição estadual ao buscar', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        abrirSeletor();

        expect(await screen.findByText('Ana Ribeiro')).toBeInTheDocument();
        expect(screen.getByText('IE: IE-111')).toBeInTheDocument();
        expect(screen.getByText('Bruno Alves')).toBeInTheDocument();
    });

    test('filtra os produtores pelo que foi digitado e avisa quando não acha', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        fireEvent.change(seletorProdutor(), { target: { value: 'bruno' } });
        expect(await screen.findByText('Bruno Alves')).toBeInTheDocument();
        expect(screen.queryByText('Ana Ribeiro')).not.toBeInTheDocument();

        fireEvent.change(seletorProdutor(), { target: { value: 'ninguem' } });
        expect(await screen.findByText('Nenhum produtor encontrado')).toBeInTheDocument();
    });

    test('escolher produtor preenche o campo e libera a geração', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        // sem produtor não há o que gerar
        expect(botaoGerar()).toBeDisabled();

        await escolherProdutor('Ana Ribeiro');

        expect(seletorProdutor()).toHaveValue('Ana Ribeiro');
        expect(botaoGerar()).toBeEnabled();
    });

    test('produtor sem inscrição estadual é sinalizado antes de gerar', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        expect(screen.queryByText(/não possui Inscrição Estadual/)).not.toBeInTheDocument();
        await escolherProdutor('Bruno Alves');

        expect(
            screen.getByText(/Este produtor não possui Inscrição Estadual cadastrada/)
        ).toBeInTheDocument();
    });

    test('gera os registros e mostra a prévia em tabela com o total', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.click(botaoGerar());

        await waitFor(() => expect(screen.getByText('Dados Gerados (2 registros)')).toBeInTheDocument());
        expect(setNotification).toHaveBeenCalledWith({
            message: '2 registros gerados com sucesso!',
            type: 'success',
        });

        // colunas e conteúdo das linhas
        expect(screen.getByText('CD_PRODUTOR_IE')).toBeInTheDocument();
        expect(screen.getByText('2026-03-05')).toBeInTheDocument();
        expect(screen.getByText('2026-03-06')).toBeInTheDocument();
        // a mesma placa aparece nas duas coletas
        expect(screen.getAllByText('ABC-1D23')).toHaveLength(2);
        expect(screen.getByText('120')).toBeInTheDocument();
        expect(screen.getByText('80')).toBeInTheDocument();
        // total do rodapé (120 + 80)
        expect(screen.getByText('Total').closest('tr')).toHaveTextContent('200 L');
    });

    test('período escolhido é o enviado para a geração', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.change(screen.getByRole('combobox'), { target: { value: '7' } });
        fireEvent.change(screen.getByDisplayValue(String(new Date().getFullYear())), {
            target: { value: '2025' },
        });
        fireEvent.click(botaoGerar());

        await waitFor(() => expect(apiService.gerarNotaFiscal).toHaveBeenCalled());
        // O id do produtor é TEXTO (ver src/utils/ids.js): os ids do backend
        // são Snowflake e não cabem em um número do JavaScript.
        expect(apiService.gerarNotaFiscal).toHaveBeenCalledWith('t', {
            produtorId: '1',
            ano: 2025,
            mes: 7,
            usarDadosSimulados: false,
        });
    });

    test('dados simulados revelam as opções de simulação e as envia na geração', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        // as opções só existem no modo simulado
        expect(screen.queryByText('Opções de Simulação')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Dados Simulados' }));
        expect(screen.getByText('Opções de Simulação')).toBeInTheDocument();
        expect(screen.getByDisplayValue('15')).toBeInTheDocument();

        fireEvent.change(screen.getByDisplayValue('15'), { target: { value: '20' } });
        fireEvent.change(screen.getByDisplayValue('300'), { target: { value: '310' } });
        fireEvent.change(screen.getByDisplayValue('400'), { target: { value: '420' } });

        await escolherProdutor('Ana Ribeiro');
        fireEvent.click(botaoGerar());

        await waitFor(() => expect(apiService.gerarNotaFiscal).toHaveBeenCalled());
        expect(apiService.gerarNotaFiscal).toHaveBeenCalledWith('t', expect.objectContaining({
            usarDadosSimulados: true,
            quantidadeDiasColeta: 20,
            valorMinimo: 310,
            valorMaximo: 420,
        }));
    });

    test('voltar para "Dados Reais" esconde as opções de simulação', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());

        fireEvent.click(screen.getByRole('button', { name: 'Dados Simulados' }));
        expect(screen.getByText('Opções de Simulação')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Dados Reais' }));
        expect(screen.queryByText('Opções de Simulação')).not.toBeInTheDocument();
    });

    test('período sem dados avisa e não abre a tabela', async () => {
        apiService.gerarNotaFiscal.mockResolvedValue([]);
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.click(botaoGerar());

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Nenhum dado encontrado para o período.',
                type: 'info',
            })
        );
        expect(screen.queryByText(/Dados Gerados/)).not.toBeInTheDocument();
    });

    test('resposta nula do backend também é tratada como "nenhum dado"', async () => {
        apiService.gerarNotaFiscal.mockResolvedValue(null);
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.click(botaoGerar());

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Nenhum dado encontrado para o período.',
                type: 'info',
            })
        );
    });

    test('erro ao gerar mostra a mensagem do servidor e libera a tela', async () => {
        apiService.gerarNotaFiscal.mockRejectedValue(new Error('produtor sem contrato ativo'));
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.click(botaoGerar());

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Erro ao gerar dados: produtor sem contrato ativo',
                type: 'error',
            })
        );
        expect(botaoGerar()).toBeEnabled();
    });

    test('erro ao carregar os produtores avisa o usuário', async () => {
        apiService.getAllProducers.mockRejectedValue(new Error('sem conexão'));
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Erro ao carregar lista de produtores.',
                type: 'error',
            })
        );
        // sem lista, nada a gerar
        expect(botaoGerar()).toBeDisabled();
    });

    test('registro sem código de produtor exibe "-", e o total ignora litros nulos', async () => {
        apiService.gerarNotaFiscal.mockResolvedValue([
            { dtRecebimento: '2026-03-07', qtLitros: null, cdPlaca: 'XYZ-9A88' },
        ]);
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        fireEvent.click(botaoGerar());

        await waitFor(() => expect(screen.getByText('Dados Gerados (1 registros)')).toBeInTheDocument());
        expect(screen.getByText('-')).toBeInTheDocument();
        expect(screen.getByText('Total').closest('tr')).toHaveTextContent('0 L');
    });

    test('exportar antes de gerar é bloqueado com aviso', async () => {
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');

        // o botão de exportar só existe depois de gerar
        expect(screen.queryByRole('button', { name: /Exportar XLSX/ })).not.toBeInTheDocument();
        expect(screen.queryByText('Gere os dados primeiro antes de exportar.')).not.toBeInTheDocument();
    });

    test('exporta o XLSX e avisa o sucesso', async () => {
        const createObjectURL = jest.fn(() => 'blob:fake');
        const revokeObjectURL = jest.fn();
        window.URL.createObjectURL = createObjectURL;
        window.URL.revokeObjectURL = revokeObjectURL;
        // o link de download é criado e removido em sequência; sem o append no
        // body o jsdom não chega a disparar o click do <a> com download.
        const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
            expect(this.download).toMatch(/^nota_fiscal_\d{4}_\d{2}\.xlsx$/);
        });

        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');
        fireEvent.click(botaoGerar());
        await waitFor(() => expect(screen.getByText('Dados Gerados (2 registros)')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: /Exportar XLSX/ }));

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Arquivo XLSX exportado com sucesso!',
                type: 'success',
            })
        );
        expect(apiService.exportarNotaFiscalXlsx).toHaveBeenCalledWith('t', {
            produtorId: '1',
            ano: new Date().getFullYear(),
            mes: new Date().getMonth() + 1,
            usarDadosSimulados: false,
        });
        expect(createObjectURL).toHaveBeenCalledTimes(1);
        expect(revokeObjectURL).toHaveBeenCalledWith('blob:fake');
        expect(clickSpy).toHaveBeenCalledTimes(1);
        clickSpy.mockRestore();
    });

    test('erro ao exportar mostra a mensagem e reabilita o botão', async () => {
        apiService.exportarNotaFiscalXlsx.mockRejectedValue(new Error('arquivo corrompido'));
        render(<NotaFiscalPage apiService={apiService} token="t" setNotification={setNotification} />);
        await waitFor(() => expect(apiService.getAllProducers).toHaveBeenCalled());
        await escolherProdutor('Ana Ribeiro');
        fireEvent.click(botaoGerar());
        await waitFor(() => expect(screen.getByText('Dados Gerados (2 registros)')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: /Exportar XLSX/ }));

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Erro ao exportar: arquivo corrompido',
                type: 'error',
            })
        );
        expect(screen.getByRole('button', { name: /Exportar XLSX/ })).toBeEnabled();
    });
});
