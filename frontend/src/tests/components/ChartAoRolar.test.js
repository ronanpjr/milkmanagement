import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import ChartAoRolar from '../../components/ChartAoRolar';

// jsdom não implementa IntersectionObserver. Estes testes simulam um para
// exercitar o caminho "entrou na viewport".
class FakeIntersectionObserver {
    constructor(callback, options) {
        this.callback = callback;
        this.options = options;
        this.elements = [];
        FakeIntersectionObserver.instances.push(this);
    }

    observe(element) {
        this.elements.push(element);
    }

    disconnect() {
        this.elements = [];
        this.disconnected = true;
    }

    // Dispara o callback como se o elemento tivesse entrado na viewport.
    trigger(isIntersecting = true) {
        this.callback(this.elements.map((target) => ({ target, isIntersecting })), this);
    }
}

describe('ChartAoRolar', () => {
    beforeEach(() => {
        FakeIntersectionObserver.instances = [];
        global.IntersectionObserver = FakeIntersectionObserver;
    });

    afterEach(() => {
        delete global.IntersectionObserver;
        jest.clearAllMocks();
    });

    test('não renderiza o gráfico antes de ele entrar na viewport', () => {
        render(
            <ChartAoRolar altura={300}>
                <div data-testid="grafico">gráfico</div>
            </ChartAoRolar>
        );

        expect(screen.queryByTestId('grafico')).not.toBeInTheDocument();
        // o espaço é reservado, para a rolagem não "pular"
        const observer = FakeIntersectionObserver.instances[0];
        expect(observer.elements).toHaveLength(1);
    });

    test('renderiza o gráfico quando ele entra na viewport', async () => {
        render(
            <ChartAoRolar altura={300}>
                <div data-testid="grafico">gráfico</div>
            </ChartAoRolar>
        );

        const observer = FakeIntersectionObserver.instances[0];
        act(() => observer.trigger(true));

        await waitFor(() => expect(screen.getByTestId('grafico')).toBeInTheDocument());
        // e para de observar: o gráfico não é remontado a cada rolagem
        expect(observer.disconnected).toBe(true);
    });

    test('não renderiza enquanto o elemento não estiver visível', async () => {
        render(
            <ChartAoRolar altura={300}>
                <div data-testid="grafico">gráfico</div>
            </ChartAoRolar>
        );

        act(() => FakeIntersectionObserver.instances[0].trigger(false));

        await waitFor(() => expect(screen.queryByTestId('grafico')).not.toBeInTheDocument());
    });

    test('sem IntersectionObserver, renderiza na hora (degrade, não defeito)', () => {
        delete global.IntersectionObserver;

        render(
            <ChartAoRolar altura={300}>
                <div data-testid="grafico">gráfico</div>
            </ChartAoRolar>
        );

        expect(screen.getByTestId('grafico')).toBeInTheDocument();
    });
});
