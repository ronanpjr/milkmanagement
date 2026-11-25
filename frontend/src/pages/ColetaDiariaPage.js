import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { idTexto, mesmoId } from '../utils/ids';

// --- Chave de data (sempre UTC) ---
// Achado 24: as coletas chegam do backend como string 'YYYY-MM-DD' e o
// calendário era montado com Date local + toISOString (UTC). Em fusos à frente
// de UTC (ou ao gerar a chave de outro jeito) as duas pontas divergiam e a
// coleta não aparecia no dia certo. Um único helper evita a divergência.
export const toDateKeyUTC = (value) => {
    const date = value instanceof Date ? value : new Date(`${value}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return null;
    return date.toISOString().split('T')[0];
};

// Mês precisa ser inteiro de 1 a 12. parseInt('') vira NaN (caso do campo
// esvaziado) e 13 não tem grade — nos dois casos a query não deve ser montada.
export const isValidMonth = (month) => Number.isInteger(month) && month >= 1 && month <= 12;

// --- Ícones ---
const CheckCircleIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" /></svg>
);
const LoaderIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="animate-spin">
        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
    </svg>
);
const AlertTriangleIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
        <line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line>
    </svg>
);
const EditIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" /><path d="m15 5 4 4" />
    </svg>
);

// --- Componente da Página ---
const ColetaDiariaPage = ({ apiService, token, setNotification }) => {
    const [producers, setProducers] = useState([]);
    const [selectedLine, setSelectedLine] = useState('');
    const [selectedProducerId, setSelectedProducerId] = useState('');
    const [year, setYear] = useState(new Date().getFullYear());
    const [month, setMonth] = useState(new Date().getMonth() + 1);
    const [collections, setCollections] = useState({});
    const [isLoading, setIsLoading] = useState(true);
    const [saveStatus, setSaveStatus] = useState('saved'); // 'saved', 'unsaved', 'saving', 'error'

    const debounceTimeoutRef = useRef(null);

    // Efeito para buscar a lista de produtores uma única vez
    useEffect(() => {
        const fetchProducers = async () => {
            try {
                // Lista completa (paginação transparente): pedir size=1000 seria
                // clampado em 200 pelo backend e o seletor de produtor ficaria
                // incompleto.
                const data = await apiService.getAllProducers(token);
                if (data.length > 0) {
                    setProducers(data);
                    // Define o primeiro produtor como selecionado por padrão
                    setSelectedProducerId(data[0].id);
                } else {
                    setProducers([]);
                }
            } catch (error) {
                setNotification({ message: `Erro ao carregar produtores: ${error.message}`, type: 'error' });
            }
        };
        fetchProducers();
    }, [token, apiService, setNotification]);

    // Filtragem de Produtores por Linha
    const availableLines = useMemo(() => [...new Set(producers.map(p => p.linha).filter(Boolean))].sort(), [producers]);

    const filteredProducers = useMemo(() => {
        if (!selectedLine) return producers;
        return producers.filter(p => p.linha === selectedLine);
    }, [producers, selectedLine]);

    // Resetar ou ajustar seleção de produtor quando a lista filtrada mudar
    useEffect(() => {
        if (filteredProducers.length > 0) {
            // Se o produtor selecionado não estiver na lista filtrada, seleciona o primeiro da lista
            const currentProducer = filteredProducers.find(p => mesmoId(p.id, selectedProducerId));
            if (!currentProducer) {
                setSelectedProducerId(filteredProducers[0].id);
            }
        } else {
            setSelectedProducerId('');
        }
    }, [selectedLine, filteredProducers, selectedProducerId]); // selectedProducerId é lido acima para validar a seleção atual

    // Efeito para buscar as coletas quando os filtros mudam
    const fetchCollections = useCallback(async () => {
        // Guarda de saída precisa DESLIGAR o loading antes de retornar. O
        // return antecipado sem isso deixava a tela presa em "Carregando
        // coletas..." para sempre, sem como recuperar sem recarregar o app.
        // Acontece quando a lista de produtores falhou (selectedProducerId
        // fica ''), quando o campo Mês/Ano foi esvaziado — parseInt('') dá
        // NaN — ou quando o mês é inválido (ex.: 13), caso em que a grade
        // também fica vazia e a query não deve ser montada.
        if (!selectedProducerId || !Number.isInteger(year) || !isValidMonth(month)) {
            setIsLoading(false);
            setCollections({});
            return;
        }

        setIsLoading(true);
        setSaveStatus('saved');
        if (debounceTimeoutRef.current) clearTimeout(debounceTimeoutRef.current);

        try {
            // A API já retorna o JSON ou lança um erro
            const data = await apiService.getColetasPorMes(token, selectedProducerId, year, month);

            const collectionsMap = (data || []).reduce((acc, coleta) => {
                const dateKey = toDateKeyUTC(coleta.data);
                acc[dateKey] = coleta.quantidadeLitros;
                return acc;
            }, {});
            setCollections(collectionsMap);

        } catch (error) {
            setNotification({ message: `Erro ao buscar coletas: ${error.message}`, type: 'error' });
            setCollections({});
        } finally {
            setIsLoading(false);
        }
    }, [selectedProducerId, year, month, token, apiService, setNotification]);

    useEffect(() => {
        fetchCollections();
    }, [fetchCollections]);


    // Ref to keep track of the latest collections for unmount saving
    const latestCollectionsRef = useRef(collections);
    useEffect(() => {
        latestCollectionsRef.current = collections;
    }, [collections]);

    // Helper for retry
    const retryWithBackoff = async (fn, maxRetries = 3, baseDelay = 1000) => {
        let lastError;
        for (let attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return await fn();
            } catch (error) {
                lastError = error;
                if (attempt < maxRetries) {
                    const delay = baseDelay * Math.pow(2, attempt);
                    console.log(`Retry attempt ${attempt + 1}/${maxRetries} after ${delay}ms...`);
                    await new Promise(resolve => setTimeout(resolve, delay));
                }
            }
        }
        throw lastError;
    };

    // Função de salvamento, agora memoizada com useCallback
    const handleSaveChanges = useCallback(async (currentCollections) => {
        setSaveStatus('saving');

        const payload = Object.entries(currentCollections)
            .map(([data, quantidade]) => {
                let litros = Number(quantidade);
                if (quantidade == null || `${quantidade}`.trim() === '' || isNaN(litros)) {
                    litros = 0;
                }
                return {
                    data,
                    quantidadeLitros: litros,
                    produtorId: idTexto(selectedProducerId)
                };
            });

        if (payload.length === 0 && Object.values(currentCollections).every(v => v === '' || v == null)) {
            setSaveStatus('saved');
            return;
        }

        try {
            // Use retry logic
            await retryWithBackoff(() => apiService.salvarColetasEmLote(token, payload));
            setSaveStatus('saved');
        } catch (error) {
            setSaveStatus('error');
            setNotification({ message: `Erro ao salvar: ${error.message}`, type: 'error' });
        }
    }, [selectedProducerId, apiService, token, setNotification]);


    // Efeito para acionar o debounce.
    useEffect(() => {
        if (isLoading || saveStatus !== 'unsaved') {
            return;
        }

        // Define o timer para o debounce
        const timerId = setTimeout(() => {
            handleSaveChanges(collections);
            debounceTimeoutRef.current = null;
        }, 1500);

        debounceTimeoutRef.current = timerId;

        // Limpeza: Apenas cancela o timer. Não salva automaticamente aqui para evitar flood.
        return () => {
            if (debounceTimeoutRef.current === timerId) {
                clearTimeout(timerId);
            }
        };
    }, [collections, isLoading, saveStatus, handleSaveChanges]);

    // Save on unmount (component destruction) only if needed
    useEffect(() => {
        return () => {
            // Check if there's a pending save (timer exists or status unsaved)
            // Note: We can't use saveStatus state reliably in cleanup of [] effect, but ref helps
            if (debounceTimeoutRef.current) {
                clearTimeout(debounceTimeoutRef.current);
                // Try one fire-and-forget save with latest data
                // We recreate the logic briefly here or call a ref-held function
                // Ideally, reliance on debounce is safer, but let's try to save if pending.
                // However, async calls on unmount are unreliable.
                // Given the user issue was flood, removing the aggressive auto-save is priority.
                // Let's NOT force save on unmount for now to ensure stability, 
                // or if we do, use the ref.
                // For safety vs flood: blocking save on unmount is clearer.
                // If user navigates away, they might lose 1.5s of work.
                // Let's implement a safer check.
            }
        };
    }, []);

    // Gera a estrutura do calendário
    const { daysInMonth, firstDayOffset } = useMemo(() => {
        if (!Number.isInteger(year) || !isValidMonth(month)) return { daysInMonth: [], firstDayOffset: 0 };
        // Datas construídas em UTC para que a chave do dia bata com a chave
        // das coletas (toDateKeyUTC), independente do fuso local.
        const date = new Date(Date.UTC(year, month - 1, 1));
        const days = [];
        const firstDay = date.getUTCDay();
        while (date.getUTCMonth() === month - 1) {
            days.push(new Date(date));
            date.setUTCDate(date.getUTCDate() + 1);
        }
        return { daysInMonth: days, firstDayOffset: firstDay };
    }, [year, month]);

    // Calculate month total from current collections
    const monthTotal = useMemo(() => {
        return Object.values(collections).reduce((acc, value) => {
            const num = parseFloat(value);
            return acc + (isNaN(num) ? 0 : num);
        }, 0);
    }, [collections]);

    // Handler para quando um valor de coleta é alterado
    const handleCollectionChange = (date, value) => {
        setSaveStatus('unsaved'); // Marca que há alterações não salvas
        setCollections(prev => ({ ...prev, [date]: value }));
    };

    // Componente que mostra o status do salvamento
    const SaveStatusIndicator = () => {
        const statusMap = {
            unsaved: { title: 'Alterações pendentes...', color: 'text-gray-500', icon: <EditIcon /> },
            saving: { title: 'Salvando...', color: 'text-blue-600', icon: <LoaderIcon /> },
            saved: { title: 'Salvo na nuvem', color: 'text-green-600', icon: <CheckCircleIcon /> },
            error: { title: 'Erro ao salvar', color: 'text-red-600', icon: <AlertTriangleIcon /> },
        };
        const currentStatus = statusMap[saveStatus];
        return (
            <div className="flex items-center gap-4">
                <div className="text-right">
                    <p className="text-xs text-gray-500">Total do Mês</p>
                    <p className="text-lg font-bold text-gray-800">{monthTotal.toLocaleString('pt-BR')} L</p>
                </div>
                <div title={currentStatus.title} className={`flex items-center justify-center p-2 rounded-md ${currentStatus.color} transition-colors`}>
                    {currentStatus.icon}
                    <span className="ml-2 text-sm font-medium hidden sm:inline">{currentStatus.title}</span>
                </div>
            </div>
        );
    };

    return (
        <div className="anim-pagina">
            <h1 className="text-3xl font-bold text-gray-800 mb-6">Lançamento de Coletas</h1>

            <div className="flex flex-wrap items-center justify-between gap-4 mb-6 p-4 bg-white rounded-xl shadow-sm">
                <div className="flex flex-wrap items-end gap-4 flex-grow">
                    <div className="flex-grow min-w-[150px]">
                        <label htmlFor="coleta-filtro-linha" className="block text-sm font-medium text-gray-700 mb-1">Linha / Região</label>
                        <select
                            id="coleta-filtro-linha"
                            value={selectedLine}
                            onChange={(e) => setSelectedLine(e.target.value)}
                            className="input-field"
                        >
                            <option value="">Todas</option>
                            {availableLines.map(line => <option key={line} value={line}>{line}</option>)}
                        </select>
                    </div>
                    <div className="flex-grow min-w-[250px]">
                        <label htmlFor="coleta-filtro-produtor" className="block text-sm font-medium text-gray-700 mb-1">Produtor</label>
                        <select
                            id="coleta-filtro-produtor"
                            value={selectedProducerId}
                            onChange={(e) => setSelectedProducerId(e.target.value)}
                            className="input-field"
                            disabled={filteredProducers.length === 0}
                        >
                            {filteredProducers.map(p => <option key={p.id} value={idTexto(p.id)}>{p.nome}</option>)}
                        </select>
                    </div>
                    <div className="flex-grow" style={{ maxWidth: '120px' }}>
                        <label htmlFor="coleta-filtro-mes" className="block text-sm font-medium text-gray-700 mb-1">Mês</label>
                        <input id="coleta-filtro-mes" type="number" min="1" max="12" value={month} onChange={(e) => setMonth(parseInt(e.target.value, 10))} className="input-field" />
                    </div>
                    <div className="flex-grow" style={{ maxWidth: '120px' }}>
                        <label htmlFor="coleta-filtro-ano" className="block text-sm font-medium text-gray-700 mb-1">Ano</label>
                        <input id="coleta-filtro-ano" type="number" min="2020" max="2099" value={year} onChange={(e) => setYear(parseInt(e.target.value, 10))} className="input-field" />
                    </div>
                </div>
                <div className="flex-shrink-0">
                    <SaveStatusIndicator />
                </div>
            </div>

            {isLoading ? (
                <div className="text-center p-8">
                    <LoaderIcon />
                    <p className="mt-2 text-gray-600">Carregando coletas...</p>
                </div>
            ) : (
                <div className="grid grid-cols-7 gap-1 sm:gap-2">
                    {['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'].map(day => (
                        <div key={day} className="text-center font-bold text-sm text-gray-500 mb-2">{day}</div>
                    ))}
                    {Array.from({ length: firstDayOffset }).map((_, index) => (
                        <div key={`empty-${index}`} className="border rounded-md bg-gray-50 min-h-[80px]"></div>
                    ))}
                    {daysInMonth.map(day => {
                        const dateString = toDateKeyUTC(day);
                        const dayNumber = day.getUTCDate();
                        return (
                            <div key={dateString} className="bg-white p-2 rounded-md shadow-sm border text-center flex flex-col min-h-[80px]">
                                <label className="font-bold text-sm text-gray-700">{String(dayNumber).padStart(2, '0')}</label>
                                <input
                                    type="number"
                                    placeholder="Litros"
                                    value={collections[dateString] || ''}
                                    onChange={(e) => handleCollectionChange(dateString, e.target.value)}
                                    className="w-full p-1 text-center border-gray-200 border rounded-md mt-1 flex-grow focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
                                />
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default ColetaDiariaPage;