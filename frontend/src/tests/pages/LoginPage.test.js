import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LoginPage from '../../pages/LoginPage';
import { apiService } from '../../services/apiService';

jest.mock('../../services/apiService');

describe('LoginPage', () => {
    let onLoginSuccess;

    const preencherFormulario = (email = 'admin@erp-demo.local', senha = 'senha123') => {
        fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
        fireEvent.change(screen.getByLabelText('Senha'), { target: { value: senha } });
    };

    beforeEach(() => {
        onLoginSuccess = jest.fn();
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('renderiza o formulário de entrada em branco', () => {
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        expect(screen.getByText('Supply & Production ERP')).toBeInTheDocument();
        expect(screen.getByLabelText('Email')).toHaveValue('');
        expect(screen.getByLabelText('Senha')).toHaveValue('');
        expect(screen.getByLabelText('Lembrar de mim')).not.toBeChecked();
        expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
        expect(screen.queryByText('Entrando...')).not.toBeInTheDocument();
    });

    test('login válido entra e repassa os tokens com "lembrar de mim" desmarcado', async () => {
        apiService.login.mockResolvedValue({ token: 'access-123', refreshToken: 'refresh-456' });
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        await waitFor(() => expect(onLoginSuccess).toHaveBeenCalledWith('access-123', 'refresh-456', false));
        expect(screen.queryByText(/Não foi possível entrar/)).not.toBeInTheDocument();
    });

    test('marcar "lembrar de mim" faz a sessão ser remembered', async () => {
        apiService.login.mockResolvedValue({ token: 'access-123', refreshToken: 'refresh-456' });
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByLabelText('Lembrar de mim'));
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        await waitFor(() => expect(onLoginSuccess).toHaveBeenCalledWith('access-123', 'refresh-456', true));
    });

    test('envia as credenciais digitadas ao serviço de login', async () => {
        apiService.login.mockResolvedValue({ token: 't', refreshToken: 'r' });
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario('joana@cooperativa.com', 'outra-senha');
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        await waitFor(() => expect(apiService.login).toHaveBeenCalledWith('joana@cooperativa.com', 'outra-senha'));
    });

    test('credencial inválida (401) mostra o erro do servidor e não entra', async () => {
        apiService.login.mockRejectedValue(new Error('Credenciais inválidas'));
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario('nobody@erp-demo.local', 'errada');
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        expect(await screen.findByText('Credenciais inválidas')).toBeInTheDocument();
        expect(onLoginSuccess).not.toHaveBeenCalled();
        // a tela continua no login, com o botão utilizável de novo
        expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
    });

    test('erro de rede mostra mensagem de falha e libera o formulário', async () => {
        apiService.login.mockRejectedValue(new Error('Failed to fetch'));
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        expect(await screen.findByText('Failed to fetch')).toBeInTheDocument();
        expect(onLoginSuccess).not.toHaveBeenCalled();
    });

    test('resposta sem token é tratada como erro em vez de "entrar" quebrado', async () => {
        apiService.login.mockResolvedValue({ refreshToken: 'refresh-456' });
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        expect(
            await screen.findByText('O servidor respondeu sem o token de autenticação.')
        ).toBeInTheDocument();
        expect(onLoginSuccess).not.toHaveBeenCalled();
    });

    test('o erro some quando o usuário tenta entrar de novo', async () => {
        apiService.login.mockRejectedValueOnce(new Error('Credenciais inválidas'));
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));
        expect(await screen.findByText('Credenciais inválidas')).toBeInTheDocument();

        apiService.login.mockResolvedValueOnce({ token: 'access-123', refreshToken: 'refresh-456' });
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        await waitFor(() => expect(onLoginSuccess).toHaveBeenCalled());
        expect(screen.queryByText('Credenciais inválidas')).not.toBeInTheDocument();
    });

    test('falha sem objeto de erro mostra a mensagem genérica de credencial', async () => {
        // some camada (fetch/timeout) pode rejeitar sem Error: o usuário ainda
        // precisa de um texto acionável em vez de uma tela muda.
        apiService.login.mockRejectedValue(undefined);
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        expect(
            await screen.findByText('Não foi possível entrar. Verifique suas credenciais e tente novamente.')
        ).toBeInTheDocument();
        expect(onLoginSuccess).not.toHaveBeenCalled();
    });

    test('mostra "Entrando..." e bloqueia o botão durante a requisição', async () => {
        let liberar;
        apiService.login.mockReturnValue(new Promise((resolve) => { liberar = resolve; }));
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        preencherFormulario();
        fireEvent.click(screen.getByRole('button', { name: 'Entrar' }));

        const botao = await screen.findByRole('button', { name: 'Entrando...' });
        expect(botao).toBeDisabled();

        liberar({ token: 'access-123', refreshToken: 'refresh-456' });
        await waitFor(() => expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled());
    });

    test('email e senha são obrigatórios, com os tipos e tipos de teclado corretos', () => {
        // A validação é a nativa do navegador (atributo required), então o que
        // se verifica aqui é o contrato do markup entregue ao usuário.
        render(<LoginPage onLoginSuccess={onLoginSuccess} />);

        expect(screen.getByLabelText('Email')).toBeRequired();
        expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email');
        expect(screen.getByLabelText('Senha')).toBeRequired();
        expect(screen.getByLabelText('Senha')).toHaveAttribute('type', 'password');
    });
});
