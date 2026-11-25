import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import TitleBar from '../../components/TitleBar';

describe('TitleBar', () => {
    const originalElectronAPI = window.electronAPI;

    afterEach(() => {
        if (originalElectronAPI === undefined) {
            delete window.electronAPI;
        } else {
            window.electronAPI = originalElectronAPI;
        }
    });

    test('não renderiza nada fora do Electron (navegador comum)', () => {
        delete window.electronAPI;
        const { container } = render(<TitleBar />);
        expect(container).toBeEmptyDOMElement();
    });

    test('renderiza a barra com a marca do app quando está no Electron', () => {
        window.electronAPI = { minimize: jest.fn(), maximize: jest.fn(), close: jest.fn() };
        render(<TitleBar />);

        expect(screen.getByText('ERP AI OCR')).toBeInTheDocument();
        expect(screen.getByAltText('Logo')).toBeInTheDocument();
        // minimize, maximize, close
        expect(screen.getAllByRole('button')).toHaveLength(3);
    });

    test('cada botão da janela chama o comando correspondente do Electron', () => {
        window.electronAPI = { minimize: jest.fn(), maximize: jest.fn(), close: jest.fn() };
        render(<TitleBar />);

        const [minimize, maximize, close] = screen.getAllByRole('button');
        fireEvent.click(minimize);
        fireEvent.click(maximize);
        fireEvent.click(close);

        expect(window.electronAPI.minimize).toHaveBeenCalledTimes(1);
        expect(window.electronAPI.maximize).toHaveBeenCalledTimes(1);
        expect(window.electronAPI.close).toHaveBeenCalledTimes(1);
    });

    test('a barra é arrastável mas os controles da janela não', () => {
        // Sem isso o usuário não consegue mover a janela: clicar em minimizar/
        // maximizar/arrastar nos botões em vez de arrastar a barra.
        window.electronAPI = { minimize: jest.fn(), maximize: jest.fn(), close: jest.fn() };
        const { container } = render(<TitleBar />);

        expect(container.firstChild).toHaveClass('app-drag');
        // marca e botões de janela ficam em contêineres app-no-drag
        screen.getAllByRole('button').forEach((botao) => {
            expect(botao.closest('.app-no-drag')).not.toBeNull();
        });
    });
});
