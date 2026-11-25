import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import UsuariosPage from '../../pages/UsuariosPage';
import { apiService } from '../../services/apiService';

jest.mock('../../services/apiService');

describe('UsuariosPage', () => {
    let setNotification;

    const preencher = (nome = 'Joana da Silva', email = 'joana@cooperativa.com', senha = 'senha123') => {
        fireEvent.change(screen.getByLabelText('Nome Completo'), { target: { value: nome } });
        fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
        fireEvent.change(screen.getByLabelText('Senha'), { target: { value: senha } });
    };

    beforeEach(() => {
        setNotification = jest.fn();
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test('renderiza o formulário de novo usuário em branco', () => {
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        expect(screen.getByText('Usuários')).toBeInTheDocument();
        expect(screen.getByLabelText('Nome Completo')).toHaveValue('');
        expect(screen.getByLabelText('Email')).toHaveValue('');
        expect(screen.getByLabelText('Senha')).toHaveValue('');
        expect(screen.getByRole('button', { name: 'Criar usuário' })).toBeEnabled();
    });

    test('usuário criado com sucesso avisa e limpa o formulário', async () => {
        apiService.register.mockResolvedValue({ id: 7 });
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher();
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Usuário criado com sucesso!',
                type: 'success',
            })
        );
        // limpa para o admin cadastrar o próximo sem clicar em limpar
        expect(screen.getByLabelText('Nome Completo')).toHaveValue('');
        expect(screen.getByLabelText('Email')).toHaveValue('');
        expect(screen.getByLabelText('Senha')).toHaveValue('');
    });

    test('envia nome, email e senha junto com o token do admin logado', async () => {
        apiService.register.mockResolvedValue({ id: 7 });
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher('Joana da Silva', 'joana@cooperativa.com', 'senha123');
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        await waitFor(() =>
            expect(apiService.register).toHaveBeenCalledWith(
                'fake-token',
                'Joana da Silva',
                'joana@cooperativa.com',
                'senha123'
            )
        );
    });

    test('email já cadastrado mostra o erro e preserva o que foi digitado', async () => {
        apiService.register.mockRejectedValue(new Error('E-mail já cadastrado'));
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher();
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        expect(await screen.findByText('E-mail já cadastrado')).toBeInTheDocument();
        // o admin precisa ver o que deu errado e corrigir só o campo ruim
        expect(screen.getByLabelText('Nome Completo')).toHaveValue('Joana da Silva');
        expect(screen.getByLabelText('Email')).toHaveValue('joana@cooperativa.com');
        expect(setNotification).not.toHaveBeenCalled();
    });

    test('falha sem mensagem do servidor mostra o texto padrão', async () => {
        apiService.register.mockRejectedValue(new Error(''));
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher();
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        expect(await screen.findByText('Não foi possível criar o usuário.')).toBeInTheDocument();
    });

    test('erro do servidor não bloqueia uma nova tentativa', async () => {
        apiService.register.mockRejectedValueOnce(new Error('Falha temporária'));
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher();
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));
        expect(await screen.findByText('Falha temporária')).toBeInTheDocument();

        apiService.register.mockResolvedValueOnce({ id: 7 });
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        await waitFor(() =>
            expect(setNotification).toHaveBeenCalledWith({
                message: 'Usuário criado com sucesso!',
                type: 'success',
            })
        );
        expect(screen.queryByText('Falha temporária')).not.toBeInTheDocument();
    });

    test('mostra "Criando..." e bloqueia o envio duplicado durante a requisição', async () => {
        let liberar;
        apiService.register.mockReturnValue(new Promise((resolve) => { liberar = resolve; }));
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        preencher();
        fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }));

        const botao = await screen.findByRole('button', { name: 'Criando...' });
        expect(botao).toBeDisabled();

        liberar({ id: 7 });
        await waitFor(() => expect(screen.getByRole('button', { name: 'Criar usuário' })).toBeEnabled());
    });

    test('os três campos são obrigatórios e o email valida formato', () => {
        // A validação é a nativa do navegador (atributo required), então o que
        // se verifica aqui é o contrato do markup entregue ao usuário.
        render(<UsuariosPage apiService={apiService} token="fake-token" setNotification={setNotification} />);

        expect(screen.getByLabelText('Nome Completo')).toBeRequired();
        expect(screen.getByLabelText('Email')).toBeRequired();
        expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email');
        expect(screen.getByLabelText('Senha')).toBeRequired();
        expect(screen.getByLabelText('Senha')).toHaveAttribute('type', 'password');
    });
});
