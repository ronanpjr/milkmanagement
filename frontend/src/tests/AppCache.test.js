import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import App from '../App';
import { apiService } from '../services/apiService';

jest.mock('../services/apiService');

// Espelha a lista de caches que o App limpa no logout. Se uma tela passar a
// gravar uma chave nova e o App não souber dela, este teste avisa.
const CACHES = [
    'dashboard_stats_cache',
    'dashboard_chart_cache',
    'producers_cache_v2',
    'fechamentos_cache_v2',
    'producers_cache_v7', // versão futura: o cache é versionado
];

// Semeadura no formato que cada tela espera reidificar no mount. O dado
// pessoal (chavePix / inscricaoEstadual) e o que torna o vazamento relevante.
const semear = () => {
    localStorage.setItem('dashboard_stats_cache', JSON.stringify({
        produtoresAtivos: 3, litrosColetadosMes: 1000, pagamentosPendentes: 0, fechamentosPendentes: 1,
    }));
    localStorage.setItem('dashboard_chart_cache', JSON.stringify([{ name: 'Jan/26', Produção: 100 }]));
    localStorage.setItem('producers_cache_v2', JSON.stringify([
        { id: 1, nome: 'Produtor A', chavePix: 'pix-secreto', inscricaoEstadual: 'IE-CONFIDENTIAL' },
    ]));
    localStorage.setItem('fechamentos_cache_v2', JSON.stringify([]));
};

describe('Logout e isolamento de cache', () => {
    beforeEach(() => {
        localStorage.clear();
        sessionStorage.clear();
        jest.clearAllMocks();
        // O App com token monta o MainLayout, que já renderiza o Dashboard.
        apiService.getProducers.mockResolvedValue({ content: [], totalPages: 0, totalElements: 0 });
        apiService.getAllProducers.mockResolvedValue([]);
        // Perfil do usuário logado (GET /api/auth/me), consultado pelo App ao
        // abrir a sessão para decidir o que é exclusivo de ADMIN.
        apiService.getMe.mockResolvedValue({ email: 'u@serra.com', nome: 'U', role: 'ADMIN' });
        apiService.getAllColetas.mockResolvedValue([]);
        apiService.getAllFechamentos.mockResolvedValue([]);
    });

    afterEach(() => {
        localStorage.clear();
        sessionStorage.clear();
    });

    test('o logout remove os caches de negócio, não só os tokens', async () => {
        localStorage.setItem('authToken', 'fake-token');
        localStorage.setItem('refreshToken', 'fake-refresh');
        semear();

        // Com token, o App renderiza o MainLayout e sai pela tela de login.
        render(<App />);

        fireEvent.click(await screen.findByText('Sair'));

        await waitFor(() => {
            expect(screen.getByRole('button', { name: /Entrar/i })).toBeInTheDocument();
        });

        CACHES.forEach((key) => {
            expect(localStorage.getItem(key)).toBeNull();
        });
    });

});
