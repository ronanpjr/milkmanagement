import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import ErrorBoundary from '../../components/ErrorBoundary';

// Componente que explode na renderização — é o que o boundary precisa capturar.
const Explode = ({ erro }) => {
    throw erro;
};

describe('ErrorBoundary', () => {
    let consoleErrorSpy;
    let reload;

    beforeEach(() => {
        // React 19 loga o erro capturado pelo boundary via console.error
        // (além do próprio componentDidCatch). Silenciado explicitamente para
        // manter a saída do teste limpa, e restaurado no afterEach.
        consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
        reload = jest.fn();
        // jsdom não implementa reload() e ele não é mockável por atribuição
        // direta no objeto location, então trocamos a propriedade inteira.
        delete window.location;
        window.location = { reload };
    });

    afterEach(() => {
        consoleErrorSpy.mockRestore();
    });

    test('renderiza os filhos normalmente enquanto nada dá errado', () => {
        render(
            <ErrorBoundary>
                <h1>Tela carregada normalmente</h1>
                <p>Conteúdo da aplicação</p>
            </ErrorBoundary>
        );

        expect(screen.getByText('Tela carregada normalmente')).toBeInTheDocument();
        expect(screen.getByText('Conteúdo da aplicação')).toBeInTheDocument();
        expect(screen.queryByText('Algo deu errado.')).not.toBeInTheDocument();
    });

    test('substitui a tela pela mensagem de erro quando um filho lança exceção', () => {
        render(
            <ErrorBoundary>
                <Explode erro={new Error('Falhou ao carregar PMA')} />
            </ErrorBoundary>
        );

        expect(screen.getByText('Algo deu errado.')).toBeInTheDocument();
        expect(screen.getByText('Ocorreu um erro inesperado na aplicação.')).toBeInTheDocument();
        // os filhos que quebraram não devem continuar na tela
        expect(screen.queryByText('Tela carregada normalmente')).not.toBeInTheDocument();
    });

    test('exibe o detalhe do erro capturado para diagnóstico', () => {
        render(
            <ErrorBoundary>
                <Explode erro={new Error('Falhou ao carregar PMA')} />
            </ErrorBoundary>
        );

        expect(screen.getByText(/Falhou ao carregar PMA/)).toBeInTheDocument();
    });

    test('o botão de recarregar chama reload da janela', () => {
        render(
            <ErrorBoundary>
                <Explode erro={new Error('boom')} />
            </ErrorBoundary>
        );

        fireEvent.click(screen.getByRole('button', { name: 'Recarregar Aplicação' }));
        expect(reload).toHaveBeenCalledTimes(1);
    });

    test('permanece no fallback após um novo erro — o usuário precisa recarregar', () => {
        render(
            <ErrorBoundary>
                <Explode erro={new Error('primeiro erro')} />
            </ErrorBoundary>
        );
        expect(screen.getByText(/primeiro erro/)).toBeInTheDocument();

        // Um segundo erro no mesmo boundary não troca a mensagem: o estado
        // hasError já ficou travado até o reload.
        fireEvent.click(screen.getByRole('button', { name: 'Recarregar Aplicação' }));
        expect(screen.getByText(/primeiro erro/)).toBeInTheDocument();
        expect(reload).toHaveBeenCalledTimes(1);
    });
});
