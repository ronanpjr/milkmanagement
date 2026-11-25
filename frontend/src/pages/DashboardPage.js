// src/pages/DashboardPage.js
import React, { useState, useEffect, useRef } from 'react';
import { apiService } from '../services/apiService';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { UsersIcon, MilkIcon, CalendarIcon, ClipboardIcon } from '../components/icons';
import { chaveMes, chaveMesDeData, ultimosMeses } from '../utils/calendar';
import { idTexto } from '../utils/ids';

// --- Cache Keys ---
const CACHE_KEYS = {
    DASHBOARD_STATS: 'dashboard_stats_cache',
    DASHBOARD_CHART: 'dashboard_chart_cache'
};

// --- Helper functions for localStorage caching ---
const getFromCache = (key) => {
    try {
        const cached = localStorage.getItem(key);
        return cached ? JSON.parse(cached) : null;
    } catch {
        return null;
    }
};

const saveToCache = (key, data) => {
    try {
        localStorage.setItem(key, JSON.stringify(data));
    } catch {
        // Silently fail if localStorage is full or unavailable
    }
};

const DashboardPage = ({ token, setNotification, onNavigate }) => {
    // Initialize state from cache for instant display
    const cachedStats = getFromCache(CACHE_KEYS.DASHBOARD_STATS);
    const cachedChart = getFromCache(CACHE_KEYS.DASHBOARD_CHART);

    const [stats, setStats] = useState(cachedStats || { produtoresAtivos: 0, litrosColetadosMes: 0, pagamentosPendentes: 0, fechamentosPendentes: 0 });
    const [chartData, setChartData] = useState(cachedChart || []);

    // Only show loading if we have no cached data
    const [isLoading, setIsLoading] = useState(!cachedStats || !cachedChart);
    const [isRefreshing, setIsRefreshing] = useState(false);

    // Achado 19: cada consulta tem seu próprio estado de erro, para que "falhou"
    // nunca seja exibido como "sem dados" (0 / lista vazia).
    const [fetchErrors, setFetchErrors] = useState({});
    // Achado 20: a listagem bateu no teto de paginação (totais podem estar incompletos).
    const [truncado, setTruncado] = useState(false);

    // O cache só interessa no primeiro render (evita tela vazia), mas o efeito
    // de busca o lia diretamente. Como getFromCache devolve JSON.parse, cada
    // chamada devolve um objeto NOVO — colocá-lo no array de dependências
    // re-dispararia o efeito a cada render, em loop de requisições. Por isso
    // o "já tinha cache?" vira um ref lido no efeito, que tem identidade
    // estável e não participa das dependências.
    const hadCachedDataRef = useRef(Boolean(cachedStats && cachedChart));

    useEffect(() => {
        // D8: sem isto, sair da tela no meio da carga deixava setState rodando
        // em componente desmontado, e trocar de aba durante o carregamento
        // podia sobrescrever os dados novos com os da requisição anterior —
        // getAllProducers percorre até 50 páginas, então a janela é longa.
        let cancelled = false;

        // Achado 19: NÃO engolir o erro com `.catch(() => [])`. Cada consulta é
        // envolvida aqui para registrar FALHA (banner) em vez de virar lista
        // vazia em silêncio — "falhou" ≠ "sem dados".
        const carregar = async (fn) => {
            try {
                return { ok: true, data: await fn() };
            } catch (error) {
                console.error('Dashboard Error:', error);
                return { ok: false };
            }
        };

        // Achado 20: o aviso de truncamento do wrapper eleva a um banner, para o
        // usuário saber que os totais podem estar subestimados.
        const marcarTruncado = () => {
            if (!cancelled) setTruncado(true);
        };

        const fetchData = async () => {
            // Se já havia cache, atualiza em segundo plano sem spinner
            if (hadCachedDataRef.current) {
                setIsRefreshing(true);
            } else {
                setIsLoading(true);
            }

            try {
                const today = new Date();
                const currentMonth = today.getMonth() + 1;
                const currentYear = today.getFullYear();

                // Format dates for current month coletas
                const firstDay = new Date(currentYear, currentMonth - 1, 1).toISOString().split('T')[0];
                const lastDay = new Date(currentYear, currentMonth, 0).toISOString().split('T')[0];

                // Usa os métodos `*ComAviso` (paginação transparente + aviso de
                // truncamento): pedir size=1000+ direto faria o backend devolver só
                // os 200 primeiros (cap de PaginationUtil/BT-4), truncando os totais.
                const [producersRes, collectionsRes, closingsRes] = await Promise.all([
                    carregar(() => apiService.getAllProducersComAviso(token, marcarTruncado)),
                    carregar(() => apiService.getAllColetasComAviso(token, { inicio: firstDay, fim: lastDay }, marcarTruncado)),
                    carregar(() => apiService.getAllFechamentosComAviso(token, marcarTruncado)),
                ]);

                const errors = {};
                if (!producersRes.ok) errors.produtores = true;
                if (!collectionsRes.ok) errors.coletas = true;
                if (!closingsRes.ok) errors.fechamentos = true;

                const producers = producersRes.ok ? producersRes.data : [];
                const collections = collectionsRes.ok ? collectionsRes.data : [];
                const closings = closingsRes.ok ? closingsRes.data : [];

                const activeProducers = producers.filter(p => p.ativo);
                const produtoresAtivos = activeProducers.length;

                const litrosColetadosMes = collections.reduce((acc, c) => acc + (c.quantidadeLitros || 0), 0);

                const pagamentosPendentes = closings
                    .filter(f => f.statusPagamento === 'Pendente')
                    .reduce((acc, f) => acc + (f.totalLiquido || 0), 0);

                // --- LÓGICA DO NOVO CARD ---
                // Calculate previous month (handle January -> December of previous year)
                const prevMonth = currentMonth === 1 ? 12 : currentMonth - 1;
                const prevYear = currentMonth === 1 ? currentYear - 1 : currentYear;

                // 1. Pega os IDs de todos os produtores que JÁ TÊM fechamento para o mês anterior.
                const producersWithClosing = new Set(
                    closings
                        .filter(f => f.mes === prevMonth && f.ano === prevYear)
                        .map(f => idTexto(f.produtorId))
                );

                // 2. Conta quantos produtores ativos NÃO ESTÃO na lista de quem já tem fechamento.
                const fechamentosPendentes = activeProducers.filter(p => !producersWithClosing.has(idTexto(p.id))).length;

                const newStats = { produtoresAtivos, litrosColetadosMes, pagamentosPendentes, fechamentosPendentes };
                if (cancelled) return;
                setStats(newStats);
                // Não grava stats parciais no cache: um erro não pode virar "0"
                // permanente na próxima abertura (isso reintroduziria o achado 19).
                if (!errors.produtores && !errors.coletas && !errors.fechamentos) {
                    saveToCache(CACHE_KEYS.DASHBOARD_STATS, newStats);
                }

                // --- Lógica do Gráfico ---
                // Fetch last 6 months coletas for the chart
                const sixMonthsAgo = new Date(today.getFullYear(), today.getMonth() - 5, 1).toISOString().split('T')[0];
                const recentesRes = await carregar(() => apiService.getAllColetasComAviso(token, { inicio: sixMonthsAgo }, marcarTruncado));
                if (!recentesRes.ok) errors.grafico = true;
                const allRecentColetas = recentesRes.ok ? recentesRes.data : [];

                const monthlyProduction = {};
                // Chave de mês/ano calculada por tabela, sem `Intl` por linha
                // (issue #34): `toLocaleString('pt-BR', {month:'short'})` em
                // cima de ~7.000 linhas travava a main thread por ~1,5 s.
                //
                // `ultimosMeses` devolve o ano de cada mês corretamente — o
                // código anterior rotulava os seis meses com o ano corrente,
                // o que (a) mostrava o ano errado na virada de ano e (b) fazia
                // as coletas dos meses do ano anterior caírem FORA do gráfico,
                // porque a chave do balde não batia com a da coleta.
                ultimosMeses(6, today).forEach(({ ano, mes }) => {
                    monthlyProduction[chaveMes(ano, mes)] = 0;
                });

                allRecentColetas.forEach(c => {
                    const monthKey = chaveMesDeData(c.data);
                    if (monthKey && monthKey in monthlyProduction) {
                        monthlyProduction[monthKey] += (c.quantidadeLitros || 0);
                    }
                });

                const chartDataFormatted = Object.keys(monthlyProduction).map(key => ({
                    name: key,
                    'Produção': monthlyProduction[key],
                }));

                if (cancelled) return;
                setFetchErrors(errors);
                if (!errors.grafico) {
                    setChartData(chartDataFormatted);
                    saveToCache(CACHE_KEYS.DASHBOARD_CHART, chartDataFormatted);
                }

            } catch (error) {
                setNotification({ message: 'Não foi possível carregar os dados do dashboard.', type: 'error' });
                console.error('Dashboard Error:', error);
            } finally {
                if (!cancelled) {
                    setIsLoading(false);
                    setIsRefreshing(false);
                }
            }
        };
        fetchData();

        return () => { cancelled = true; };
    }, [token, setNotification]);

    const formatCurrency = (value) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value || 0);

    // --- ATUALIZAÇÃO DOS CARDS ---
    const summaryData = [
        { title: "Produtores Ativos", value: stats.produtoresAtivos, error: fetchErrors.produtores, icon: <UsersIcon />, color: "blue", linkTo: 'producers' },
        { title: "Litros Coletados (Mês)", value: `${stats.litrosColetadosMes.toLocaleString('pt-BR')} L`, error: fetchErrors.coletas, icon: <MilkIcon />, color: "green", linkTo: 'collections' },
        { title: "Pagamentos Pendentes", value: formatCurrency(stats.pagamentosPendentes), error: fetchErrors.fechamentos, icon: <CalendarIcon />, color: "orange", linkTo: 'closings' },
        { title: "Fechamentos Pendentes (Mês)", value: stats.fechamentosPendentes, error: fetchErrors.produtores || fetchErrors.fechamentos, icon: <ClipboardIcon />, color: "indigo", linkTo: 'closings' },
    ];

    // Achado 19: nomes legíveis das consultas que falharam, para o banner.
    const falhas = [];
    if (fetchErrors.produtores) falhas.push('produtores');
    if (fetchErrors.coletas) falhas.push('coletas do mês');
    if (fetchErrors.fechamentos) falhas.push('fechamentos');
    if (fetchErrors.grafico) falhas.push('série do gráfico');

    const colorMapping = { blue: 'border-blue-500', green: 'border-green-500', orange: 'border-orange-500', indigo: 'border-indigo-500' };
    const bgMapping = { blue: 'bg-blue-100', green: 'bg-green-100', orange: 'bg-orange-100', indigo: 'bg-indigo-100' };
    const textMapping = { blue: 'text-blue-600', green: 'text-green-600', orange: 'text-orange-600', indigo: 'text-indigo-600' };

    if (isLoading) {
        return <div className="text-center p-8">Carregando dados do dashboard...</div>;
    }

    return (
        <div>
            <div className="flex items-center gap-3 mb-6">
                <h1 className="text-3xl font-bold text-gray-800">Dashboard</h1>
                {isRefreshing && (
                    <span className="text-sm text-gray-500 animate-pulse">Atualizando...</span>
                )}
            </div>
            {falhas.length > 0 && (
                <div role="alert" className="mb-6 rounded-lg border border-red-300 bg-red-50 p-4 text-red-700">
                    Não foi possível carregar: {falhas.join(', ')}. Os valores abaixo podem estar incompletos.
                </div>
            )}
            {truncado && (
                <div role="status" className="mb-6 rounded-lg border border-yellow-300 bg-yellow-50 p-4 text-yellow-800">
                    Alguns totais podem estar incompletos: a listagem excedeu o limite de paginação.
                </div>
            )}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
                {summaryData.map(item => (
                    <div
                        key={item.title}
                        onClick={() => onNavigate && onNavigate(item.linkTo)}
                        className={`dashboard-card anim-entrada bg-white p-6 rounded-xl shadow-md flex items-center border-l-4 ${colorMapping[item.color]} cursor-pointer transform transition-transform duration-200 hover:scale-105 hover:shadow-xl`}
                    >
                        <div className={`p-3 rounded-full ${bgMapping[item.color]} ${textMapping[item.color]} mr-4`}>
                            {item.icon}
                        </div>
                        <div>
                            <p className="text-gray-500 text-sm">{item.title}</p>
                            <p className="text-xl font-bold text-gray-800 break-words">{item.error ? '—' : item.value}</p>
                        </div>
                    </div>
                ))}
            </div>

            <div className={`dashboard-chart anim-entrada bg-white p-6 rounded-xl shadow-md`}>
                <h2 className="text-xl font-semibold text-gray-700 mb-4">Produção Semestral (Litros)</h2>
                {fetchErrors.grafico ? (
                    <p role="alert" className="text-red-600">Não foi possível carregar a série do gráfico.</p>
                ) : (
                    <div style={{ width: '100%', height: 300 }}>
                        <ResponsiveContainer>
                            <BarChart data={chartData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis dataKey="name" />
                                <YAxis width={60} />
                                <Tooltip formatter={(value) => `${value.toLocaleString('pt-BR')} L`} />
                                <Legend />
                                {/* A animação de entrada continua, mas curta: o
                                    padrão do recharts é 1.500 ms, e cada frame
                                    recalcula o path SVG da série. Com 1.500 ms,
                                    a animação segurava a main thread por um
                                    segundo e meio — visível como "choppy" —
                                    enquanto o gráfico de 6 barras não tem
                                    nada a informar com tanta duração. Ver issue #35. */}
                                <Bar dataKey="Produção" fill="#3b82f6" animationDuration={300} animationEasing="ease-out" />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                )}
            </div>
        </div>
    );
};

export default DashboardPage;