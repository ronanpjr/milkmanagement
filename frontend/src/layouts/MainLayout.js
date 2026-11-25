import React, { useState } from 'react';
import { apiService } from '../services/apiService';
import { DashboardIcon, UsersIcon, ClipboardIcon, CalendarIcon, ChartIcon, LogoutIcon, MenuIcon, ScanIcon, FileSpreadsheetIcon, UserPlusIcon } from '../components/icons';
import logo from '../logo.svg';
import DashboardPage from '../pages/DashboardPage';
import ProdutorPage from '../pages/ProdutorPage';
import ColetaDiariaPage from '../pages/ColetaDiariaPage';
import FechamentoPage from '../pages/FechamentoPage';
import ReportsPage from '../pages/ReportsPage';
import ImportacaoColetasPage from '../pages/ImportacaoColetasPage';
import NotaFiscalPage from '../pages/NotaFiscalPage';
import UsuariosPage from '../pages/UsuariosPage';

// A tela de Nota Fiscal está DESATIVADA TEMPORARIAMENTE.
//
// A feature em si é legítima e não está sendo removida: o CLAUDE.md é
// explícito em que os dados estimados da Nota Fiscal (a planilha de crédito
// presumido do governo de MG) existem para conferência, e devem aparecer
// como estimados. O que se desliga aqui é o ACESSO, para a apresentação.
//
// O ponto único de desligar é esta constante. Antes, o item do menu, o import
// da página e o case do roteamento ficavam comentados em três lugares — e
// código comentado não é código: ele some no próximo merge e ninguém sabe
// que a feature existe. Aqui, voltar a exibi-la é trocar um `false` por
// `true`.
//
// A tela também não fica acessível por URL (currentPage='notafiscal'): quem
// chegar lá por estado salvo cai no Dashboard, em vez de numa tela que não
// deveria estar disponível.
const MOSTRAR_NOTA_FISCAL = false;


const MainLayout = ({ currentPage, onNavigate, onLogout, token, setNotification, perfil }) => {
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);
    // ADMIN vê todas as telas; USER não vê a de Usuários, porque só ADMIN
    // pode registrar usuário (não há auto-cadastro). enquanto `perfil` não
    // carregou, a tela fica oculta — melhor não mostrar um botão que não
    // funciona do que piscar na tela a cada login.
    const ehAdmin = perfil?.role === 'ADMIN';

    const navItems = [
        { id: 'dashboard', label: 'Dashboard', icon: <DashboardIcon /> },
        { id: 'producers', label: 'Produtores', icon: <UsersIcon /> },
        { id: 'collections', label: 'Coletas', icon: <ClipboardIcon /> },
        { id: 'closings', label: 'Fechamentos', icon: <CalendarIcon /> },
        { id: 'reports', label: 'Relatórios', icon: <ChartIcon /> },
        { id: 'import', label: 'Importar Coletas', icon: <ScanIcon /> },
        { id: 'notafiscal', label: 'Nota Fiscal', icon: <FileSpreadsheetIcon />, oculto: !MOSTRAR_NOTA_FISCAL },
        { id: 'users', label: 'Usuários', icon: <UserPlusIcon />, somenteAdmin: true },
    ].filter(item => (!item.somenteAdmin || ehAdmin) && !item.oculto);

    const handleNavigateAndCloseSidebar = (page) => {
        onNavigate(page);
        setIsSidebarOpen(false);
    }

    const renderPage = () => {
        switch (currentPage) {
            case 'dashboard': return <DashboardPage token={token} setNotification={setNotification} onNavigate={onNavigate} />;
            case 'producers': return <ProdutorPage apiService={apiService} token={token} setNotification={setNotification} />;
            case 'collections': return <ColetaDiariaPage apiService={apiService} token={token} setNotification={setNotification} />;
            case 'closings': return <FechamentoPage apiService={apiService} token={token} setNotification={setNotification} />;
            case 'reports': return <ReportsPage apiService={apiService} token={token} setNotification={setNotification} />;
            case 'import': return <ImportacaoColetasPage apiService={apiService} token={token} setNotification={setNotification} />;
            case 'notafiscal': return MOSTRAR_NOTA_FISCAL
                ? <NotaFiscalPage apiService={apiService} token={token} setNotification={setNotification} />
                // Fora da apresentação, a tela não existe: quem chegar por
                // estado salvo cai no Dashboard, em vez de ver uma tela que
                // está desligada de propósito.
                : <DashboardPage token={token} setNotification={setNotification} onNavigate={onNavigate} />;
            // Guarda de rota: se um USER chegar aqui por estado salvo ou
            // qualquer outra via, cai no Dashboard em vez de renderizar uma
            // tela que só faz sentido para ADMIN.
            case 'users': return ehAdmin
                ? <UsuariosPage apiService={apiService} token={token} setNotification={setNotification} />
                : <DashboardPage token={token} setNotification={setNotification} onNavigate={onNavigate} />;
            default: return <DashboardPage token={token} setNotification={setNotification} />;
        }
    };

    return (
        <div className="relative min-h-screen md:flex pt-8"> {/* Added pt-8 for TitleBar */}


            {/* Overlay para fechar a sidebar em telas pequenas */}
            <div onClick={() => setIsSidebarOpen(false)} className={`fixed inset-0 bg-black bg-opacity-50 z-20 md:hidden ${isSidebarOpen ? 'block' : 'hidden'} top-8`}></div>

            {/* Sidebar */}
            <aside className={`fixed top-8 left-0 h-[calc(100%-2rem)] w-64 bg-gray-800 text-white flex flex-col z-30 sidebar-transition md:translate-x-0 ${isSidebarOpen ? 'translate-x-0' : '-translate-x-full'}`}>
                <div className="h-24 flex items-center px-6 border-b border-gray-700">
                    <img src={logo} alt="Logo" className="h-16 w-auto object-contain" />
                    <h1 className="text-xl font-bold ml-3 text-white">Acme Supply Co.</h1>
                </div>
                <nav className="flex-1 px-4 py-6">
                    <ul>
                        {navItems.map(item => (
                            <li key={item.id}>
                                <button
                                    onClick={() => handleNavigateAndCloseSidebar(item.id)}
                                    className={`w-full flex items-center px-4 py-3 rounded-lg transition-colors duration-200 ${currentPage === item.id ? 'bg-blue-600' : 'hover:bg-gray-700'}`}
                                >
                                    {item.icon}
                                    <span className="ml-4">{item.label}</span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </nav>
                <div className="p-4 border-t border-gray-700">
                    <button onClick={onLogout} className="w-full flex items-center px-4 py-3 rounded-lg hover:bg-gray-700 transition-colors duration-200">
                        <LogoutIcon />
                        <span className="ml-4">Sair</span>
                    </button>
                </div>
            </aside>

            {/* Área de Conteúdo Principal */}
            <div className="flex-1 flex flex-col md:ml-64 h-[calc(100vh-2rem)]"> {/* Adjusted height */}
                {/* Botão de Menu para telas pequenas */}
                <button onClick={() => setIsSidebarOpen(true)} className="md:hidden p-4 text-gray-600 self-start">
                    <MenuIcon />
                </button>

                <main className="flex-1 overflow-x-hidden overflow-y-auto bg-gray-100 p-4 md:p-6 pt-0 md:pt-6">
                    {renderPage()}
                </main>
            </div>
        </div>
    );
};

export default MainLayout;