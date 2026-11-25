import React from 'react';
import { render, screen, act } from '@testing-library/react';
import Notification from '../../components/Notification';

describe('Notification', () => {
    beforeEach(() => {
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.runOnlyPendingTimers();
        jest.useRealTimers();
    });

    test('não renderiza nada quando não há mensagem', () => {
        const { container } = render(<Notification message="" type="success" onDismiss={jest.fn()} />);
        expect(container).toBeEmptyDOMElement();
    });

    test('mostra a mensagem informada ao usuário', () => {
        render(<Notification message="Usuário criado com sucesso!" type="success" onDismiss={jest.fn()} />);
        expect(screen.getByText('Usuário criado com sucesso!')).toBeInTheDocument();
    });

    test('aplica o estilo verde para success e vermelho para error', () => {
        const { rerender } = render(<Notification message="Salvo" type="success" onDismiss={jest.fn()} />);
        expect(screen.getByText('Salvo')).toHaveClass('bg-green-500');

        rerender(<Notification message="Falhou" type="error" onDismiss={jest.fn()} />);
        expect(screen.getByText('Falhou')).toHaveClass('bg-red-500');
    });

    test('usa o estilo de aviso para info, que nao e erro', () => {
        // Antes este caso caia no vermelho: o ternario so distinguia success
        // de todo o resto, entao um aviso informativo aparecia com a mesma
        // cor de uma falha — o usuario nao tem como saber se deu errado.
        render(<Notification message="Aviso" type="info" onDismiss={jest.fn()} />);
        expect(screen.getByText('Aviso')).toHaveClass('bg-blue-500');
        expect(screen.getByText('Aviso')).not.toHaveClass('bg-red-500');
    });

    test('usa cinza para um tipo desconhecido, em vez de fingir que é erro', () => {
        // Um type inventado (typo, valor vindo de outra tela) nao deve
        // aparecer como falha. Cinza_neutro é o fallback honesto.
        render(<Notification message="Mensagem" type="qualquer-coisa" onDismiss={jest.fn()} />);
        expect(screen.getByText('Mensagem')).toHaveClass('bg-gray-700');
        expect(screen.getByText('Mensagem')).not.toHaveClass('bg-red-500');
    });

    test('anuncia a mensagem para leitores de tela', () => {
        render(<Notification message="Aviso" type="info" onDismiss={jest.fn()} />);
        expect(screen.getByRole('status')).toHaveTextContent('Aviso');
    });

    test('some sozinha depois de 3 segundos chamando onDismiss', () => {
        const onDismiss = jest.fn();
        render(<Notification message="Aviso temporário" type="success" onDismiss={onDismiss} />);

        // Antes de 3s continua visível e não descartou nada.
        act(() => { jest.advanceTimersByTime(2999); });
        expect(screen.getByText('Aviso temporário')).toBeInTheDocument();
        expect(onDismiss).not.toHaveBeenCalled();

        act(() => { jest.advanceTimersByTime(1); });
        expect(onDismiss).toHaveBeenCalledTimes(1);
    });

    test('cancela o timer ao desmontar antes do prazo, evitando descartar componente morto', () => {
        const onDismiss = jest.fn();
        const { unmount } = render(<Notification message="Some logo" type="success" onDismiss={onDismiss} />);

        act(() => { jest.advanceTimersByTime(2000); });
        unmount();
        act(() => { jest.advanceTimersByTime(5000); });

        expect(onDismiss).not.toHaveBeenCalled();
    });

    test('reinicia a contagem quando a mensagem muda', () => {
        const onDismiss = jest.fn();
        const { rerender } = render(<Notification message="Primeira" type="success" onDismiss={onDismiss} />);

        act(() => { jest.advanceTimersByTime(2500); });
        rerender(<Notification message="Segunda" type="success" onDismiss={onDismiss} />);

        act(() => { jest.advanceTimersByTime(2500); });
        expect(onDismiss).not.toHaveBeenCalled();

        act(() => { jest.advanceTimersByTime(500); });
        expect(onDismiss).toHaveBeenCalledTimes(1);
    });
});
