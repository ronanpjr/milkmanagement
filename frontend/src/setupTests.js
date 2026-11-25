// jest-dom adds custom jest matchers for asserting on DOM nodes.
// allows you to do things like:
// expect(element).toHaveTextContent(/react/i)
// learn more: https://github.com/testing-library/jest-dom
import '@testing-library/jest-dom';

// O jsdom não implementa ResizeObserver, que o recharts (ResponsiveContainer)
// usa para dimensionar os gráficos. Sem este stub, qualquer teste que renderize
// uma página com gráfico cai no ErrorBoundary com "ResizeObserver is not
// defined" — falha que não tem nada a ver com o que está sendo testado.
if (typeof global.ResizeObserver === 'undefined') {
    global.ResizeObserver = class ResizeObserver {
        observe() {}
        unobserve() {}
        disconnect() {}
    };
}
