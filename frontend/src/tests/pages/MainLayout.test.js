import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import MainLayout from '../../layouts/MainLayout';

// O layout só compõe as páginas: cada uma é trocada por um stub para que o
// teste do layout não dependa das chamadas de API e do estado interno delas.
jest.mock('../../pages/DashboardPage', () => () => <div>PAGINA: dashboard</div>);
jest.mock('../../pages/ProdutorPage', () => () => <div>PAGINA: producers</div>);
jest.mock('../../pages/ColetaDiariaPage', () => () => <div>PAGINA: collections</div>);
jest.mock('../../pages/FechamentoPage', () => () => <div>PAGINA: closings</div>);
jest.mock('../../pages/ReportsPage', () => () => <div>PAGINA: reports</div>);
jest.mock('../../pages/ImportacaoColetasPage', () => () => <div>PAGINA: import</div>);
jest.mock('../../pages/NotaFiscalPage', () => () => <div>PAGINA: notafiscal</div>);
jest.mock('../../pages/UsuariosPage', () => () => <div>PAGINA: users</div>);

const ADMIN = { email: 'admin@erp-demo.local', nome: 'Admin', role: 'ADMIN' };
const USER = { email: 'user@erp-demo.local', nome: 'User', role: 'USER' };

// So ADMIN registra usuario (nao ha auto-cadastro), entao "Usuarios" nao faz
// parte do menu de um USER.
const ITENS_EXCLUSIVOS_ADMIN = ['Usuários'];

// A tela de Nota Fiscal está desativada temporariamente
// (MOSTRAR_NOTA_FISCAL, em MainLayout.js). Enquanto for assim, ela não
// aparece no menu — e o teste abaixo cobre exatamente isso, para que a
// remoção seja de propósito e não um efeito colateral.
const ITENS = [
    'Dashboard',
    'Produtores',
    'Coletas',
    'Fechamentos',
    'Relatórios',
    'Importar Coletas',
    'Usuários',
];

// O mesmo menu sem o que e exclusivo de ADMIN.
const ITENS_DE_USER = ITENS.filter((r) => !ITENS_EXCLUSIVOS_ADMIN.includes(r));

describe('MainLayout', () => {
    let onNavigate;
    let onLogout;
    let setNotification;

    // Nome exato: "Coletas" também casa com "Importar Coletas".
    const itemDeNav = (rotulo) => screen.getByRole('button', { name: rotulo });
    // Para as asercoes negativas: getByRole LANCA quando nao acha, entao um
    // `expect(itemDeNav(...)).toBeUndefined()` falharia com excecao em vez de
    // falhar com uma comparacao legivel.
    const itemDeNavOpcional = (rotulo) => screen.queryByRole('button', { name: rotulo });

    // O perfil padrao e ADMIN: os testes de estrutura do menu (quantidade de
    // botoes, navegacao) querem ver o menu COMPLETO. Os casos de visibilidade
    // por role passam o perfil explicitamente.
    const montar = (currentPage = 'dashboard', perfil = ADMIN) =>
        render(
            <MainLayout
                currentPage={currentPage}
                onNavigate={onNavigate}
                onLogout={onLogout}
                token="fake-token"
                setNotification={setNotification}
                perfil={perfil}
            />
        );

    beforeEach(() => {
        onNavigate = jest.fn();
        onLogout = jest.fn();
        setNotification = jest.fn();
    });

    test('mostra a marca, o nome do app e todos os itens de menu', () => {
        montar();

        expect(screen.getByText('Acme Supply Co.')).toBeInTheDocument();
        expect(screen.getByAltText('Logo')).toBeInTheDocument();
        ITENS.forEach((rotulo) => {
            expect(itemDeNav(rotulo)).toBeInTheDocument();
        });
        expect(screen.getAllByRole('button')).toHaveLength(ITENS.length + 2); // menu + Sair + menu móvel
    });

    test('a página pedida é a que aparece no conteúdo', () => {
        montar('closings');
        expect(screen.getByText('PAGINA: closings')).toBeInTheDocument();
        // o item do menu correspondente fica marcado como o atual
        expect(itemDeNav('Fechamentos')).toHaveClass('bg-blue-600');
        expect(itemDeNav('Dashboard')).not.toHaveClass('bg-blue-600');
    });

    test.each([
        ['dashboard', 'dashboard'],
        ['producers', 'producers'],
        ['collections', 'collections'],
        ['closings', 'closings'],
        ['reports', 'reports'],
        ['import', 'import'],
        ['users', 'users'],
    ])('currentPage="%s" renderiza a página correspondente', (currentPage, esperado) => {
        montar(currentPage);
        expect(screen.getByText(`PAGINA: ${esperado}`)).toBeInTheDocument();
    });

    test('a tela de Nota Fiscal está desativada e some do menu', () => {
        // Desativação TEMPORÁRIA (MOSTRAR_NOTA_FISCAL, em MainLayout.js).
        montar();
        expect(itemDeNavOpcional('Nota Fiscal')).toBeNull();
    });

    test('a tela de Nota Fiscal desativada não abre por URL', () => {
        // Some do menu não impede chegar em 'notafiscal' por outro caminho
        // (estado salvo, histórico). Quem chega lá cai no dashboard.
        montar('notafiscal');
        expect(screen.getByText('PAGINA: dashboard')).toBeInTheDocument();
    });

    test('página desconhecida cai no dashboard em vez de tela vazia', () => {
        montar('inexistente');
        expect(screen.getByText('PAGINA: dashboard')).toBeInTheDocument();
    });

    test('clicar no menu navega para a tela escolhida', () => {
        montar();

        fireEvent.click(itemDeNav('Usuários'));
        expect(onNavigate).toHaveBeenCalledWith('users');

        fireEvent.click(itemDeNav('Relatórios'));
        expect(onNavigate).toHaveBeenLastCalledWith('reports');
    });

    test('USER nao ve o item de Usuarios, que e exclusivo de ADMIN', () => {
        // Registrar usuario e ato de ADMIN. Sem auto-cadastro, oferecer o botao
        // a um USER mostra uma tela cujas chamadas so voltam 403.
        montar('dashboard', USER);

        expect(itemDeNavOpcional('Usuários')).toBeNull();
        ITENS_DE_USER.forEach((rotulo) => {
            expect(itemDeNav(rotulo)).toBeInTheDocument();
        });
    });

    test('USER apontado para "users" cai no dashboard em vez de ver a tela', () => {
        // Some do menu nao impede chegar em 'users' por outro caminho (estado
        // salvo, history). Renderizar ali seria tela inutil e 403.
        montar('users', USER);
        expect(screen.getByText('PAGINA: dashboard')).toBeInTheDocument();
    });

    test('o item de Usuarios fica oculto enquanto o perfil carrega', () => {
        // Enquanto o App busca /api/auth/me, perfil e null. Mostrar o item e
        // depois_some-lo piscaria na tela a cada login.
        montar('dashboard', null);
        expect(itemDeNavOpcional('Usuários')).toBeNull();
    });

    test('o botão Sair encerra a sessão', () => {
        montar();
        fireEvent.click(screen.getByRole('button', { name: 'Sair' }));
        expect(onLogout).toHaveBeenCalledTimes(1);
    });

    test('a sidebar começa fechada em telas pequenas', () => {
        const { container } = montar();
        expect(container.querySelector('aside')).toHaveClass('-translate-x-full');
    });

    test('o botão de menu abre a sidebar em telas pequenas', () => {
        const { container } = montar();

        fireEvent.click(container.querySelector('main').parentElement.querySelector('button'));

        expect(container.querySelector('aside')).toHaveClass('translate-x-0');
        expect(container.querySelector('aside')).not.toHaveClass('-translate-x-full');
        expect(container.querySelector('div.fixed.inset-0')).toHaveClass('block');
    });

    test('escolher um item no menu navega e fecha a sidebar', () => {
        const { container } = montar();

        fireEvent.click(container.querySelector('main').parentElement.querySelector('button'));
        expect(container.querySelector('aside')).toHaveClass('translate-x-0');

        fireEvent.click(itemDeNav('Coletas'));

        expect(onNavigate).toHaveBeenCalledWith('collections');
        expect(container.querySelector('aside')).toHaveClass('-translate-x-full');
    });

    test('o overlay escuro fecha a sidebar sem navegar', () => {
        const { container } = montar();

        fireEvent.click(container.querySelector('main').parentElement.querySelector('button'));
        const overlay = container.querySelector('div.fixed.inset-0');
        fireEvent.click(overlay);

        expect(container.querySelector('aside')).toHaveClass('-translate-x-full');
        expect(onNavigate).not.toHaveBeenCalled();
    });
});
