import React, { useState, useEffect, useCallback, useRef } from 'react';
import { calculateFinalPrice, getBonusCBT, getBonusCCS, getBonusGordura, getBonusProteina } from '../utils/pricingLogic';
import logo from '../logo.svg';
import { idTexto, mesmoId } from '../utils/ids';

// --- Ícones (código omitido para brevidade) ---
const PlusIcon = () => <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>;
const EditIcon = () => <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" /><path d="m15 5 4 4" /></svg>;
const TrashIcon = () => <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" /><path d="M10 11v6" /><path d="M14 11v6" /><path d="M5 6l1.4-2.8A2 2 0 0 1 8.2 2h7.6a2 2 0 0 1 1.8 1.2L19 6" /></svg>;
const DollarSignIcon = () => <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="1" x2="12" y2="23"></line><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path></svg>;
const PrintIcon = () => <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="6 9 6 2 18 2 18 9"></polyline><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path><rect x="6" y="14" width="12" height="8"></rect></svg>;


// --- Componente de Seleção de Produtor com Busca ---
const SearchableProducerSelect = ({ producers, selectedProducerId, onChange, disabled }) => {
    const [searchTerm, setSearchTerm] = useState('');
    const [filterLinha, setFilterLinha] = useState('');
    const [isOpen, setIsOpen] = useState(false);
    const [highlightedIndex, setHighlightedIndex] = useState(0);
    const inputRef = React.useRef(null);
    const listRef = React.useRef(null);

    // Get unique linhas for filter dropdown
    const linhas = React.useMemo(() =>
        [...new Set(producers.map(p => p.linha).filter(Boolean))].sort(),
        [producers]);

    // Filter producers based on search term and linha
    const filteredProducers = React.useMemo(() => {
        return producers.filter(p => {
            const matchesSearch = p.nome.toLowerCase().includes(searchTerm.toLowerCase());
            const matchesLinha = filterLinha ? p.linha === filterLinha : true;
            return matchesSearch && matchesLinha;
        });
    }, [producers, searchTerm, filterLinha]);

    // Selected producer display name
    const selectedProducer = producers.find(p => mesmoId(p.id, selectedProducerId));
    const displayValue = selectedProducer ? selectedProducer.nome : '';

    // Reset highlighted index when filtered list changes
    React.useEffect(() => {
        setHighlightedIndex(0);
    }, [filteredProducers.length]);

    // Scroll highlighted item into view
    React.useEffect(() => {
        if (isOpen && listRef.current) {
            const items = listRef.current.querySelectorAll('li');
            if (items[highlightedIndex]) {
                // `behavior: 'smooth'` explícito: o app deixou de ter
                // `scroll-behavior: smooth` global (que transformava TODOS os
                // scrolls da aplicação em animados, inclusive os de listas
                // longas, onde o movimento só atrapalha), mas aqui a animação
                // é o que dá noção de "o item selecionado acompanhou o
                // teclado". Ver issue #35.
                items[highlightedIndex].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            }
        }
    }, [highlightedIndex, isOpen]);

    const handleInputChange = (e) => {
        setSearchTerm(e.target.value);
        setIsOpen(true);
    };

    const handleSelectProducer = (producerId) => {
        onChange(producerId);
        setSearchTerm('');
        setIsOpen(false);
    };

    const handleKeyDown = (e) => {
        if (!isOpen) {
            if (e.key === 'ArrowDown' || e.key === 'Enter') {
                setIsOpen(true);
                e.preventDefault();
            }
            return;
        }

        switch (e.key) {
            case 'ArrowDown':
                e.preventDefault();
                setHighlightedIndex(prev =>
                    prev < filteredProducers.length - 1 ? prev + 1 : prev
                );
                break;
            case 'ArrowUp':
                e.preventDefault();
                setHighlightedIndex(prev => prev > 0 ? prev - 1 : 0);
                break;
            case 'Enter':
                e.preventDefault();
                if (filteredProducers[highlightedIndex]) {
                    handleSelectProducer(filteredProducers[highlightedIndex].id);
                }
                break;
            case 'Escape':
                setIsOpen(false);
                setSearchTerm('');
                break;
            default:
                break;
        }
    };

    const handleBlur = (e) => {
        // Delay to allow click on list item
        setTimeout(() => {
            if (!listRef.current?.contains(document.activeElement)) {
                setIsOpen(false);
                setSearchTerm('');
            }
        }, 150);
    };

    if (disabled) {
        return (
            <input
                type="text"
                value={displayValue}
                className="input-field bg-gray-100"
                disabled
            />
        );
    }

    return (
        <div className="relative">
            {/* Linha Filter + Search Input side by side */}
            <div className="flex gap-2">
                <select
                    value={filterLinha}
                    onChange={(e) => setFilterLinha(e.target.value)}
                    className="input-field text-sm w-1/3"
                >
                    <option value="">Todas Linhas</option>
                    {linhas.map(linha => (
                        <option key={linha} value={linha}>{linha}</option>
                    ))}
                </select>

                <div className="relative flex-1">
                    <input
                        ref={inputRef}
                        type="text"
                        value={isOpen ? searchTerm : displayValue}
                        onChange={handleInputChange}
                        onFocus={() => setIsOpen(true)}
                        onBlur={handleBlur}
                        onKeyDown={handleKeyDown}
                        placeholder="Digite para buscar..."
                        className="input-field pr-8 w-full"
                    />
                    <button
                        type="button"
                        onClick={() => {
                            setIsOpen(!isOpen);
                            if (!isOpen) inputRef.current?.focus();
                        }}
                        className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                    >
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <polyline points={isOpen ? "18 15 12 9 6 15" : "6 9 12 15 18 9"}></polyline>
                        </svg>
                    </button>
                </div>
            </div>

            {/* Dropdown List */}
            {isOpen && (
                <ul
                    ref={listRef}
                    className="absolute z-50 w-full mt-1 max-h-60 overflow-auto bg-white border border-gray-300 rounded-lg shadow-lg"
                >
                    {filteredProducers.length === 0 ? (
                        <li className="px-3 py-2 text-gray-500 italic">Nenhum produtor encontrado</li>
                    ) : (
                        filteredProducers.map((producer, index) => (
                            <li
                                key={producer.id}
                                onClick={() => handleSelectProducer(producer.id)}
                                className={`px-3 py-2 cursor-pointer flex justify-between items-center ${index === highlightedIndex
                                    ? 'bg-blue-500 text-white'
                                    : 'hover:bg-gray-100'
                                    } ${mesmoId(producer.id, selectedProducerId) ? 'font-semibold' : ''}`}
                            >
                                <span>{producer.nome}</span>
                                {producer.linha && (
                                    <span className={`text-xs px-2 py-0.5 rounded ${index === highlightedIndex
                                        ? 'bg-blue-400 text-white'
                                        : 'bg-gray-200 text-gray-600'
                                        }`}>
                                        {producer.linha}
                                    </span>
                                )}
                            </li>
                        ))
                    )}
                </ul>
            )}
        </div>
    );
};

// --- Componente do Modal de Geração/Edição ---
const FechamentoModal = ({ isOpen, onClose, onSave, producers, fechamento, initialValues, apiService, token }) => {
    const [selectedProducerId, setSelectedProducerId] = useState('');
    const [dataInicio, setDataInicio] = useState('');
    const [dataFim, setDataFim] = useState('');
    const [precoLitro, setPrecoLitro] = useState('');
    const [listaDescontos, setListaDescontos] = useState([]);
    const [statusPagamento, setStatusPagamento] = useState('Pendente');
    const [cbt, setCbt] = useState('');
    const [ccs, setCcs] = useState('');
    const [gordura, setGordura] = useState('');
    const [proteina, setProteina] = useState('');
    const [fidelidade, setFidelidade] = useState('');
    const [bonusVolume, setBonusVolume] = useState('');
    const [totalLitros, setTotalLitros] = useState(null);
    const [isLoadingLitros, setIsLoadingLitros] = useState(false);

    useEffect(() => {
        if (!isOpen) return;

        if (fechamento) { // Modo Edição
            setSelectedProducerId(idTexto(fechamento.produtorId));
            setDataInicio(fechamento.dataInicio);
            setDataFim(fechamento.dataFim);
            setPrecoLitro(fechamento.precoLitro || '');
            setListaDescontos(fechamento.listaDescontos || []);
            setStatusPagamento(fechamento.statusPagamento || 'Pendente');
            setCbt(fechamento.cbt || '');
            setCcs(fechamento.ccs || '');
            setGordura(fechamento.gordura || '');
            setProteina(fechamento.proteina || '');
            setFidelidade(fechamento.bonusFidelidade || '');
            setBonusVolume(fechamento.bonusVolume || '');
        } else { // Modo Criação
            const now = new Date();
            // Default to current month if no initialValues
            const defaultFirstDay = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().split('T')[0];
            const defaultLastDay = new Date(now.getFullYear(), now.getMonth() + 1, 0).toISOString().split('T')[0];

            setSelectedProducerId(idTexto(initialValues?.produtorId) || (producers.length > 0 ? idTexto(producers[0].id) : ''));
            setDataInicio(initialValues?.dataInicio || defaultFirstDay);
            setDataFim(initialValues?.dataFim || defaultLastDay);
            setPrecoLitro('');
            setListaDescontos([]);
            setStatusPagamento('Pendente');
            setCbt('');
            setCcs('');
            setGordura('');
            setProteina('');
            setFidelidade('');
            setBonusVolume('');
        }
    }, [fechamento, producers, isOpen, initialValues]);

    // Fetch total litros when producer/dates changes (only in create mode)
    useEffect(() => {
        if (!isOpen || fechamento || !selectedProducerId || !dataInicio || !dataFim) {
            setTotalLitros(null);
            setIsLoadingLitros(false);
            return;
        }

        let cancelled = false;

        const fetchTotalLitros = async () => {
            setIsLoadingLitros(true);
            try {
                // getAllColetas percorre todas as páginas: `size: 1000` seria
                // clampado em 200 pelo backend e o total de litros do período
                // sairia subestimado.
                const coletas = await apiService.getAllColetas(token, {
                    produtorId: selectedProducerId,
                    inicio: dataInicio,
                    fim: dataFim,
                });
                if (!cancelled) {
                    const total = (Array.isArray(coletas) ? coletas : []).reduce((acc, c) => acc + (c.quantidadeLitros || 0), 0);
                    setTotalLitros(total);
                }
            } catch (err) {
                console.error('Error fetching coletas:', err);
                if (!cancelled) {
                    setTotalLitros(0); // Set to 0 instead of null on error
                }
            } finally {
                if (!cancelled) {
                    setIsLoadingLitros(false);
                }
            }
        };

        fetchTotalLitros();

        return () => { cancelled = true; };
    }, [isOpen, fechamento, selectedProducerId, dataInicio, dataFim, apiService, token]);

    const handleAddDesconto = () => {
        setListaDescontos([...listaDescontos, { descricao: '', valor: '' }]);
    };

    const handleRemoveDesconto = (index) => {
        const newDescontos = [...listaDescontos];
        newDescontos.splice(index, 1);
        setListaDescontos(newDescontos);
    };

    const handleDescontoChange = (index, field, value) => {
        const newDescontos = [...listaDescontos];
        newDescontos[index][field] = value;
        setListaDescontos(newDescontos);
    };

    const totalDescontos = listaDescontos.reduce((acc, d) => acc + (parseFloat(d.valor) || 0), 0);

    const handleSubmit = (e) => {
        e.preventDefault();

        // Remove empty discounts before saving
        const validDescontos = listaDescontos.filter(d => d.descricao && d.valor);

        // Campos numéricos opcionais: campo vazio viaja como null, NUNCA como 0.
        // `parseFloat('') || 0` convertia "não preenchido" em "zero medido", e o
        // zero cai na primeira faixa das tabelas de bônus (getBonusCBT(0) =
        // 0.08), fazendo o relatório impresso exibir bônus de qualidade para
        // quem nunca teve avaliação.
        const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : parseFloat(v));

        // Métricas de qualidade só existem para quem adere ao sistema.
        // Bônus de volume/fidelidade valem para qualquer produtor: são decisão
        // do usuário no lançamento, não tabela automática.
        const bonusFields = {
            bonusFidelidade: numOrNull(fidelidade),
            bonusVolume: numOrNull(bonusVolume)
        };
        const qualityFields = {
            cbt: numOrNull(cbt),
            ccs: numOrNull(ccs),
            gordura: numOrNull(gordura),
            proteina: numOrNull(proteina)
        };

        if (fechamento) { // Salvando Edição
            onSave({
                precoLitro: parseFloat(precoLitro) || 0,
                listaDescontos: validDescontos,
                statusPagamento: statusPagamento,
                dataPagamento: statusPagamento === 'Pago' ? new Date().toISOString().split('T')[0] : null,
                ...(isQualityPayment ? qualityFields : {}),
                ...bonusFields
            });
        } else { // Salvando Criação
            onSave({
                produtorId: idTexto(selectedProducerId),
                dataInicio,
                dataFim,
                precoLitro: parseFloat(precoLitro) || 0,
                listaDescontos: validDescontos,
                ...(isQualityPayment ? qualityFields : {}),
                ...bonusFields
            });
        }
    };

    const selectedProducer = producers.find(p => mesmoId(p.id, selectedProducerId));
    const isQualityPayment = selectedProducer?.usaPagamentoPorQualidade;

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 no-print">
            <div className="bg-white p-6 rounded-xl shadow-lg w-full max-w-5xl max-h-[90vh] overflow-y-auto">
                <h2 className="text-xl font-bold mb-4">{fechamento ? 'Editar Fechamento' : 'Gerar Novo Fechamento'}</h2>
                <form onSubmit={handleSubmit}>
                    {/* Main 3-column layout */}
                    <div className="flex gap-6">

                        {/* LEFT COLUMN: Producer, Dates, Price */}
                        <div className="w-1/4 space-y-3">
                            <div>
                                <label className="block text-gray-600 text-sm mb-1">Produtor</label>
                                <SearchableProducerSelect
                                    producers={producers}
                                    selectedProducerId={selectedProducerId}
                                    onChange={(id) => setSelectedProducerId(id)}
                                    disabled={!!fechamento}
                                />
                            </div>

                            <div>
                                <label className="block text-gray-600 text-sm mb-1">Data Início</label>
                                <input type="date" value={dataInicio} onChange={(e) => setDataInicio(e.target.value)} className="input-field text-sm w-full" disabled={!!fechamento} required />
                            </div>
                            <div>
                                <label className="block text-gray-600 text-sm mb-1">Data Fim</label>
                                <input type="date" value={dataFim} onChange={(e) => setDataFim(e.target.value)} className="input-field text-sm w-full" disabled={!!fechamento} required />
                            </div>

                            <div>
                                <label className="block text-gray-600 text-sm mb-1">{isQualityPayment ? 'Preço Base (R$)' : 'Preço/Litro (R$)'}</label>
                                <input type="number" step="0.01" value={precoLitro} onChange={(e) => setPrecoLitro(e.target.value)} className="input-field text-sm" placeholder="Ex: 2.50" />
                            </div>

                            {/* Total Litros Preview - Create Mode.
                                F7: this is a client-side estimate (calculateFinalPrice, see
                                pricingLogic.js header comment) shown only before the fechamento
                                is saved — there is no backend record yet to preview against.
                                The authoritative value, once saved, always comes from the API
                                (fechamento.totalLiquido / precoFinal), never from this calc. */}
                            {!fechamento && (
                                <div className="bg-green-50 border border-green-200 rounded-lg p-2 text-sm">
                                    <div className="flex justify-between">
                                        <span className="text-green-700">Total Litros:</span>
                                        <span className="font-bold text-green-800">
                                            {isLoadingLitros ? 'Carregando...' :
                                                totalLitros !== null ? `${totalLitros.toLocaleString('pt-BR')} L` :
                                                    '—'}
                                        </span>
                                    </div>
                                    {precoLitro && totalLitros > 0 && (
                                        <div className="flex justify-between pt-1 border-t border-green-200 mt-1">
                                            <span className="text-green-700">Valor Final:</span>
                                            <span className="font-bold text-green-800">
                                                {new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(
                                                    (() => {
                                                        // Mesma aridade do modo edição (achado 16):
                                                        // calculateFinalPrice(priceBase, cbt, ccs, gordura,
                                                        // proteina, fidelidade, bonusVolume). Não existe mais
                                                        // bônus automático de volume, então totalLitros/
                                                        // daysInPeriod não entram no cálculo.
                                                        const preview = isQualityPayment ? calculateFinalPrice(
                                                            parseFloat(precoLitro) || 0, parseFloat(cbt) || 0, parseFloat(ccs) || 0,
                                                            parseFloat(gordura) || 0, parseFloat(proteina) || 0,
                                                            parseFloat(fidelidade) || 0, bonusVolume !== '' ? parseFloat(bonusVolume) : null
                                                        ) : { finalPrice: parseFloat(precoLitro) || 0 };
                                                        return (totalLitros * preview.finalPrice) - (totalDescontos || 0);
                                                    })()
                                                )}
                                            </span>
                                        </div>
                                    )}
                                    {totalLitros === 0 && !isLoadingLitros && (
                                        <p className="text-xs text-amber-600 mt-1">Nenhuma coleta encontrada no período.</p>
                                    )}
                                </div>
                            )}

                            {fechamento && (
                                <>
                                    <div>
                                        <label className="block text-gray-600 text-sm mb-1">Status Pagamento</label>
                                        <select value={statusPagamento} onChange={(e) => setStatusPagamento(e.target.value)} className="input-field text-sm">
                                            <option value="Pendente">Pendente</option>
                                            <option value="Pago">Pago</option>
                                        </select>
                                    </div>

                                    {/* Total Litros and Payment Preview for Edit Mode.
                                        F7: also a client-side estimate of the *pending edit* — the
                                        currently-saved fechamento's own values above (totalLitros,
                                        and totalLiquido/precoFinal shown elsewhere) come from the API. */}
                                    <div className="bg-green-50 border border-green-200 rounded-lg p-2 text-sm">
                                        <div className="flex justify-between">
                                            <span className="text-green-700">Total Litros:</span>
                                            <span className="font-bold text-green-800">{fechamento.totalLitros?.toLocaleString('pt-BR')} L</span>
                                        </div>
                                        {precoLitro && (
                                            <div className="flex justify-between pt-1 border-t border-green-200 mt-1">
                                                <span className="text-green-700">Valor Final:</span>
                                                <span className="font-bold text-green-800">
                                                    {new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(
                                                        (() => {
                                                            const litros = fechamento.totalLitros || 0;
                                                            // Espelha CalculoPrecoPadrao: preço base + bônus de
                                                            // volume/fidelidade, que valem para qualquer produtor. Só as
                                                            // métricas de qualidade ficam de fora do caminho padrão.
                                                            const preview = isQualityPayment ? calculateFinalPrice(
                                                                parseFloat(precoLitro) || 0, parseFloat(cbt) || 0, parseFloat(ccs) || 0,
                                                                parseFloat(gordura) || 0, parseFloat(proteina) || 0,
                                                                parseFloat(fidelidade) || 0, bonusVolume !== '' ? parseFloat(bonusVolume) : null
                                                            ) : {
                                                                finalPrice: (parseFloat(precoLitro) || 0)
                                                                    + (bonusVolume !== '' ? (parseFloat(bonusVolume) || 0) : 0)
                                                                    + (parseFloat(fidelidade) || 0)
                                                            };
                                                            return (litros * preview.finalPrice) - (totalDescontos || 0);
                                                        })()
                                                    )}
                                                </span>
                                            </div>
                                        )}
                                    </div>
                                </>
                            )}
                        </div>

                        {/* MIDDLE COLUMN: Quality Metrics (only if quality payment) */}
                        {isQualityPayment && (
                            <div className="w-1/4 bg-blue-50 p-3 rounded-lg border border-blue-100">
                                <h3 className="text-sm font-semibold text-blue-800 mb-2">Métricas de Qualidade</h3>
                                <div className="grid grid-cols-2 gap-2">
                                    <div>
                                        <label className="block text-xs text-gray-600 mb-0.5">CBT</label>
                                        <input type="number" value={cbt} onChange={(e) => setCbt(e.target.value)} className="input-field text-sm py-1" placeholder="20" />
                                    </div>
                                    <div>
                                        <label className="block text-xs text-gray-600 mb-0.5">CCS</label>
                                        <input type="number" value={ccs} onChange={(e) => setCcs(e.target.value)} className="input-field text-sm py-1" placeholder="40" />
                                    </div>
                                    <div>
                                        <label className="block text-xs text-gray-600 mb-0.5">Gordura (%)</label>
                                        <input type="number" step="0.01" value={gordura} onChange={(e) => setGordura(e.target.value)} className="input-field text-sm py-1" placeholder="4.00" />
                                    </div>
                                    <div>
                                        <label className="block text-xs text-gray-600 mb-0.5">Proteína (%)</label>
                                        <input type="number" step="0.01" value={proteina} onChange={(e) => setProteina(e.target.value)} className="input-field text-sm py-1" placeholder="3.40" />
                                    </div>
                                </div>

                                {/* Preview — client-side estimate before saving, see F7 comment above. */}
                                {precoLitro && (
                                    <div className="mt-2 pt-2 border-t border-blue-200 text-xs">
                                        {(() => {
                                            const preview = calculateFinalPrice(
                                                parseFloat(precoLitro) || 0, parseFloat(cbt) || 0, parseFloat(ccs) || 0,
                                                parseFloat(gordura) || 0, parseFloat(proteina) || 0,
                                                parseFloat(fidelidade) || 0, bonusVolume !== '' ? parseFloat(bonusVolume) : null
                                            );
                                            const fmt = (v) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(v || 0);
                                            if (!preview) return null;
                                            return (
                                                <div className="grid grid-cols-2 gap-x-2 gap-y-0.5">
                                                    <span>Base:</span><span className="text-right">{fmt(parseFloat(precoLitro))}</span>
                                                    <span>CBT:</span><span className={`text-right ${preview.vCBT >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vCBT)}</span>
                                                    <span>CCS:</span><span className={`text-right ${preview.vCCS >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vCCS)}</span>
                                                    <span>Gordura:</span><span className={`text-right ${preview.vGordura >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vGordura)}</span>
                                                    <span>Proteína:</span><span className={`text-right ${preview.vProteina >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vProteina)}</span>
                                                    <span>Volume:</span><span className={`text-right ${preview.vVolume >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vVolume)}</span>
                                                    <span>Fidelidade:</span><span className={`text-right ${preview.vFidelidade >= 0 ? 'text-green-600' : 'text-red-600'}`}>{fmt(preview.vFidelidade)}</span>
                                                    <span className="font-bold text-blue-800 pt-1 border-t border-blue-200">Final/L:</span>
                                                    <span className="text-right font-bold text-blue-800 pt-1 border-t border-blue-200">{fmt(preview.finalPrice)}</span>
                                                </div>
                                            );
                                        })()}
                                    </div>
                                )}
                            </div>
                        )}

                        {/* Bônus comerciais — valem para QUALQUER produtor, inclusive os que
                            não aderem ao sistema de qualidade. São valores digitados no
                            lançamento (podem ser negativos, como penalidade contratual);
                            não existe tabela automática por média de litros. */}
                        <div className="w-1/4 bg-amber-50 p-3 rounded-lg border border-amber-100">
                            <h3 className="text-sm font-semibold text-amber-800 mb-2">Bônus (R$/litro)</h3>
                            <div className="grid grid-cols-1 gap-2">
                                <div>
                                    <label htmlFor="fechamento-bonus-volume" className="block text-xs text-gray-600 mb-0.5">Volume</label>
                                    <input id="fechamento-bonus-volume" type="number" step="0.01" value={bonusVolume} onChange={(e) => setBonusVolume(e.target.value)} className="input-field text-sm py-1" placeholder="0.15" />
                                </div>
                                <div>
                                    <label htmlFor="fechamento-bonus-fidelidade" className="block text-xs text-gray-600 mb-0.5">Fidelidade</label>
                                    <input id="fechamento-bonus-fidelidade" type="number" step="0.01" value={fidelidade} onChange={(e) => setFidelidade(e.target.value)} className="input-field text-sm py-1" placeholder="0.10" />
                                </div>
                            </div>
                            <p className="text-xs text-amber-700 mt-2">
                                Valores em R$/litro. Aceita negativo para penalidade contratual.
                            </p>
                        </div>

                        {/* RIGHT COLUMN: Discounts — flex-1 para ocupar o que sobra,
                            com 3 colunas fixas (produtor, bônus e, quando houver,
                            qualidade). */}
                        <div className="flex-1">
                            <div className="bg-gray-50 p-3 rounded-lg border border-gray-200 h-full">
                                <div className="flex justify-between items-center mb-2">
                                    <h3 className="text-sm font-semibold text-gray-700">Descontos</h3>
                                    <button type="button" onClick={handleAddDesconto} className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
                                        <PlusIcon /> Adicionar
                                    </button>
                                </div>

                                {listaDescontos.length === 0 && <p className="text-xs text-gray-500 italic">Nenhum desconto.</p>}

                                <div className="space-y-1 max-h-32 overflow-y-auto">
                                    {listaDescontos.map((desconto, index) => (
                                        <div key={index} className="flex gap-1 items-center">
                                            <input
                                                type="text"
                                                value={desconto.descricao}
                                                onChange={(e) => handleDescontoChange(index, 'descricao', e.target.value)}
                                                className="input-field text-sm py-1 flex-1"
                                                placeholder="Descrição"
                                            />
                                            <input
                                                type="number"
                                                step="0.01"
                                                value={desconto.valor}
                                                onChange={(e) => handleDescontoChange(index, 'valor', e.target.value)}
                                                className="input-field text-sm py-1 w-20"
                                                placeholder="R$"
                                            />
                                            <button type="button" onClick={() => handleRemoveDesconto(index)} className="text-red-500 hover:text-red-700 p-1">
                                                <TrashIcon />
                                            </button>
                                        </div>
                                    ))}
                                </div>

                                {listaDescontos.length > 0 && (
                                    <div className="mt-2 pt-2 border-t border-gray-200 flex justify-between text-sm font-semibold">
                                        <span>Total:</span>
                                        <span>{new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(totalDescontos)}</span>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>

                    {/* Action Buttons */}
                    <div className="flex justify-end gap-3 mt-4 pt-4 border-t border-gray-200">
                        <button type="button" onClick={onClose} className="py-2 px-4 bg-gray-200 text-gray-800 font-semibold rounded-lg hover:bg-gray-300 transition-colors text-sm">Cancelar</button>
                        <button type="submit" className="btn-primary text-sm">Salvar</button>
                    </div>
                </form>
            </div>
        </div>
    );
};

// --- Componente de Impressão ---
const PrintableReport = ({ reportData }) => {
    const { fechamento, coletas } = reportData;

    // Configurações de Formatação
    const formatCurrency = (value) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value || 0);
    const formatDate = (dateString) => {
        if (!dateString) return '';
        const [year, month, day] = dateString.split('-');
        return `${day}/${month}/${year}`;
    };

    // Preparação dos dados da tabela
    const start = new Date(fechamento.dataInicio);
    const end = new Date(fechamento.dataFim);
    const daysInPeriod = Math.ceil((end - start) / (1000 * 60 * 60 * 24)) + 1;

    // Create map of date string (YYYY-MM-DD) to quantity
    const coletasMap = new Map(coletas.map(c => [c.data, c.quantidadeLitros]));

    const calendarDays = [];
    for (let i = 0; i < daysInPeriod; i++) {
        const d = new Date(start);
        d.setDate(d.getDate() + i);
        const dateStrIso = d.toISOString().split('T')[0];
        const dateStrDisplay = formatDate(dateStrIso); // dd/MM/yyyy

        calendarDays.push({
            dateStr: dateStrDisplay.substring(0, 5), // dd/MM
            fullDate: dateStrDisplay,
            litros: coletasMap.has(dateStrIso) ? coletasMap.get(dateStrIso) : ''
        });
    }

    return (
        <div className="p-1 font-sans text-[10px] text-black bg-white">
            <div className="border-2 border-black mx-auto max-w-[21cm]">

                {/* Header */}
                <div className="bg-white text-center border-b border-black py-1">
                    <img src={logo} alt="ERP AI OCR" className="h-16 mx-auto object-contain" />
                </div>
                <div className="bg-yellow-100 text-center border-b border-black py-0.5 text-[10px] font-semibold uppercase">
                    PERÍODO: {formatDate(fechamento.dataInicio)} À {formatDate(fechamento.dataFim)} - FORNECEDOR
                </div>

                {/* Tabela de Dados */}
                <table className="w-full border-collapse text-[10px]">
                    <thead>
                        <tr>
                            <th className="w-1/2 border-r border-b border-black bg-gray-200 text-center py-0.5">DATA</th>
                            <th className="w-1/2 border-b border-black bg-yellow-300 text-center py-0.5 uppercase">{fechamento.produtorNome}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {calendarDays.map((item) => (
                            <tr key={item.fullDate}>
                                <td className="border-r border-b border-black bg-blue-100 text-center py-px">{item.dateStr}</td>
                                <td className="border-b border-black text-right px-2 py-px">{item.litros}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>

                {/* Footer Detailed Breakdown */}
                <div className="mt-0 border-t-2 border-black text-[10px]">
                    {/* Row: Total Litros */}
                    <div className="flex border-b border-black">
                        <div className="w-1/2 bg-blue-100 py-0.5 px-2 text-center font-bold border-r border-black">Total</div>
                        <div className="w-1/2 bg-gray-100 py-0.5 px-2 text-right font-bold">{fechamento.totalLitros} L</div>
                    </div>

                    {/* Quality Pricing Rows */}
                    <div className="flex border-b border-black">
                        <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-center font-semibold border-r border-black">Preço base</div>
                        <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-right">{formatCurrency(fechamento.precoLitro)}</div>
                    </div>

                    {fechamento.estrategiaPreco === 'Qualidade' ? (
                        <>
                            <QualityRows fechamento={fechamento} formatCurrency={formatCurrency} />
                        </>
                    ) : (
                        <>
                            {/* Standard Pricing Discounts Breakdown */}
                            {fechamento.listaDescontos && fechamento.listaDescontos.length > 0 ? (
                                fechamento.listaDescontos.map((d, idx) => (
                                    <div key={idx} className="flex border-b border-black">
                                        <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-center font-semibold border-r border-black">Desconto: {d.descricao}</div>
                                        <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-right text-red-600">
                                            -{formatCurrency(d.valor)}
                                        </div>
                                    </div>
                                ))
                            ) : (
                                <div className="flex border-b border-black">
                                    <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-center font-semibold border-r border-black">Descontos</div>
                                    <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-right text-red-600">
                                        {Math.abs(fechamento.descontos) > 0 ? `-${formatCurrency(Math.abs(fechamento.descontos))}` : 'R$ 0,00'}
                                    </div>
                                </div>
                            )}
                        </>
                    )}

                    {/* Row: Valor do Leite (antes dos descontos) - only if there are discounts */}
                    {Math.abs(fechamento.descontos) > 0 && (
                        <div className="flex border-b border-black">
                            <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-center font-semibold border-r border-black">Valor do Leite</div>
                            <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-right font-semibold">
                                {formatCurrency(fechamento.totalLiquido + Math.abs(fechamento.descontos))}
                            </div>
                        </div>
                    )}

                    <div className="flex border-black">
                        <div className="w-1/2 bg-orange-400 py-1.5 px-2 text-center font-bold text-xs border-r border-black">VALOR A PAGAR</div>
                        <div className="w-1/2 bg-orange-400 py-1.5 px-2 text-right font-bold text-xs">
                            {formatCurrency(fechamento.totalLiquido)}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

// Helper component to render the bonus breakdown on the printed report.
// F7: the itemized quality bonus amounts (vCBT/vCCS/vGordura/vProteina) are
// recomputed client-side from the stored raw metrics purely for display — the
// backend does not persist each itemized bonus, only the raw metrics plus the
// aggregate precoFinal/bonusVolume/bonusFidelidade (see the F7 comment in
// pricingLogic.js). The "R$ Total do litro" row, which is what actually gets
// charged, always uses fechamento.precoFinal from the API.
//
// CRITICAL: getBonus*() maps null → 0 (no bonus) but treats 0 as a real
// measurement landing in an extreme bracket. So each quality row is rendered
// ONLY when the producer actually has that metric. Rendering the whole table
// for a non-quality producer used to print invented bonuses (getBonusCBT(0) =
// 0.08, getBonusGordura(0) = -0.04) on a report handed to the producer.
const QualityRows = ({ fechamento, formatCurrency }) => {
    const has = (v) => v !== null && v !== undefined && v !== '';

    // Only criteria the producer was actually assessed on.
    const vGordura = has(fechamento.gordura) ? getBonusGordura(fechamento.gordura) : null;
    const vProteina = has(fechamento.proteina) ? getBonusProteina(fechamento.proteina) : null;
    const vCCS = has(fechamento.ccs) ? getBonusCCS(fechamento.ccs) : null;
    const vCBT = has(fechamento.cbt) ? getBonusCBT(fechamento.cbt) : null;

    // Volume and Fidelidade are commercial bonuses chosen at launch time —
    // they apply to any producer, so they render whenever a value is set.
    const vVolume = has(fechamento.bonusVolume) ? fechamento.bonusVolume : null;
    const vFidelidade = has(fechamento.bonusFidelidade) ? fechamento.bonusFidelidade : null;

    const renderRow = (label, value) => (
        <div className="flex border-b border-black">
            <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-center border-r border-black">{label}</div>
            <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-right">{formatCurrency(value || 0)}</div>
        </div>
    );

    return (
        <>
            {vGordura !== null && renderRow("Gordura", vGordura)}
            {vProteina !== null && renderRow("Proteína", vProteina)}
            {vVolume !== null && renderRow("Volume", vVolume)}
            {vCCS !== null && renderRow("CCS", vCCS)}
            {vCBT !== null && renderRow("CBT", vCBT)}
            {vFidelidade !== null && renderRow("Fidelidade", vFidelidade)}
            <div className="flex border-b border-black">
                <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-center font-semibold border-r border-black">R$ Total do litro</div>
                <div className="w-1/2 bg-orange-200 py-0.5 px-2 text-right font-semibold">
                    {formatCurrency(fechamento.precoFinal || fechamento.precoLitro)}
                </div>
            </div>
            {/* Discounts row for quality-based fechamentos */}
            {fechamento.listaDescontos && fechamento.listaDescontos.length > 0 ? (
                fechamento.listaDescontos.map((d, idx) => (
                    <div key={idx} className="flex border-b border-black">
                        <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-center border-r border-black">Desconto: {d.descricao}</div>
                        <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-right text-red-600">
                            -{formatCurrency(d.valor)}
                        </div>
                    </div>
                ))
            ) : (
                <div className="flex border-b border-black">
                    <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-center border-r border-black">Descontos</div>
                    <div className="w-1/2 bg-orange-100 py-0.5 px-2 text-right text-red-600">
                        {Math.abs(fechamento.descontos) > 0 ? `-${formatCurrency(Math.abs(fechamento.descontos))}` : 'R$ 0,00'}
                    </div>
                </div>
            )}
        </>
    );
};
const PrintableBulkReport = ({ bulkReportData }) => {
    // useEffect removido - controle centralizado no componente pai

    return (
        <div>
            {bulkReportData.map((data) => (
                <div key={data.fechamento.id} className="page-break">
                    <PrintableReport reportData={data} />
                </div>
            ))}
        </div>
    );
};

// --- Cache Keys ---
const CACHE_VERSION = 'v2'; // Bump this to bust all caches on deploy
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
const CACHE_KEYS = {
    FECHAMENTOS: `fechamentos_cache_${CACHE_VERSION}`,
    PRODUCERS: `producers_cache_${CACHE_VERSION}`
};

// --- Helper functions for localStorage caching (with TTL) ---
const getFromCache = (key) => {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) return null;
        const { data, timestamp } = JSON.parse(raw);
        if (Date.now() - timestamp > CACHE_TTL_MS) {
            localStorage.removeItem(key);
            return null;
        }
        return data;
    } catch {
        return null;
    }
};

const saveToCache = (key, data) => {
    try {
        localStorage.setItem(key, JSON.stringify({ data, timestamp: Date.now() }));
    } catch {
        // Silently fail if localStorage is full or unavailable
    }
};

// --- Página Principal de Fechamentos ---
const FechamentoPage = ({ apiService, token, setNotification }) => {
    // Tab State
    const [activeTab, setActiveTab] = useState('historico'); // 'historico' or 'pendentes'

    // Initialize state from cache for instant display
    const [fechamentos, setFechamentos] = useState(() => {
        const cached = getFromCache(CACHE_KEYS.FECHAMENTOS);
        return Array.isArray(cached) ? cached : (cached && cached.content ? cached.content : []);
    });
    const [producers, setProducers] = useState(() => {
        const cached = getFromCache(CACHE_KEYS.PRODUCERS);
        return Array.isArray(cached) ? cached : (cached && cached.content ? cached.content : []);
    });

    // Pagination State
    // O backend limita o tamanho de página em 200 (PaginationUtil) e devolve
    // os totais reais (`totalPages`/`totalElements`) da consulta inteira, então
    // a paginação server-side abaixo funciona corretamente com 20 por página.
    // Pedir 1000 aqui não trazia "todos os registros": o servidor clampava
    // silenciosamente para 200, totalPages ficava 1 e a UI de paginação
    // nunca aparecia — a tela mostrava só os 200 fechamentos mais recentes.
    const [page, setPage] = useState(0);
    const [size] = useState(20);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    // Only show loading if we have no cached data
    const [isLoading, setIsLoading] = useState(() => {
        const hasCachedFechamentos = getFromCache(CACHE_KEYS.FECHAMENTOS);
        const hasCachedProducers = getFromCache(CACHE_KEYS.PRODUCERS);
        return !hasCachedFechamentos || !hasCachedProducers;
    });
    const [isRefreshing, setIsRefreshing] = useState(false);

    const [isModalOpen, setIsModalOpen] = useState(false);
    const [editingFechamento, setEditingFechamento] = useState(null);
    const [reportData, setReportData] = useState(null);
    const [bulkReportData, setBulkReportData] = useState(null);
    const [isPrinting, setIsPrinting] = useState(false);

    // Filters State
    const [searchTerm, setSearchTerm] = useState('');
    const [filterLine, setFilterLine] = useState('');
    const [filterMonth, setFilterMonth] = useState('');
    const [filterYear, setFilterYear] = useState('');
    const [filterStatus, setFilterStatus] = useState('');

    // Pendentes State
    const [pendingProducers, setPendingProducers] = useState([]);
    const [isLoadingPending, setIsLoadingPending] = useState(false);
    const [pendingMes, setPendingMes] = useState(new Date().getMonth() + 1); // Default to current month
    const [pendingAno, setPendingAno] = useState(new Date().getFullYear());

    // Generate month options
    const months = [
        { value: 1, label: 'Janeiro' }, { value: 2, label: 'Fevereiro' }, { value: 3, label: 'Março' },
        { value: 4, label: 'Abril' }, { value: 5, label: 'Maio' }, { value: 6, label: 'Junho' },
        { value: 7, label: 'Julho' }, { value: 8, label: 'Agosto' }, { value: 9, label: 'Setembro' },
        { value: 10, label: 'Outubro' }, { value: 11, label: 'Novembro' }, { value: 12, label: 'Dezembro' }
    ];

    // Generate year options (last 5 years)
    const currentYear = new Date().getFullYear();
    const years = Array.from({ length: 6 }, (_, i) => currentYear - i + 1); // Next year + last 5

    const fetchAllData = useCallback(async (showLoading = true) => {
        requestIdRef.current += 1;
        const requestId = requestIdRef.current;

        if (showLoading) {
            setIsLoading(true);
        } else {
            setIsRefreshing(true);
        }
        try {
            const [producers, fechamentosData] = await Promise.all([
                apiService.getAllProducers(token), // Lista completa para os dropdowns
                apiService.getFechamentos(token, { page, size, sortBy: 'dataInicio', sortDir: 'desc' })
            ]);

            const newProducers = producers || [];

            // Ensure mes/ano exist for sorting/filtering
            const newFechamentos = (fechamentosData.content || []).map(f => {
                let derivedAno = f.ano;
                let derivedMes = f.mes;

                if (!derivedAno || !derivedMes) {
                    const dateObj = new Date(f.dataInicio);
                    // Use UTC methods or simple string split to avoid timezone off-by-one errors on dates like '2024-05-01'
                    // Assuming dataInicio is YYYY-MM-DD string
                    if (f.dataInicio && f.dataInicio.includes('-')) {
                        const parts = f.dataInicio.split('-');
                        derivedAno = parseInt(parts[0]);
                        derivedMes = parseInt(parts[1]);
                    } else if (!isNaN(dateObj.getTime())) {
                        derivedAno = dateObj.getFullYear();
                        derivedMes = dateObj.getMonth() + 1;
                    }
                }

                return {
                    ...f,
                    ano: derivedAno,
                    mes: derivedMes
                };
            });

            // Requisição mais recente: as anteriores são descartadas.
            if (requestId !== requestIdRef.current) return;

            setProducers(newProducers);
            setFechamentos(newFechamentos);
            setTotalPages(fechamentosData.totalPages || 0);
            setTotalElements(fechamentosData.totalElements || 0);

            // Update cache with fresh data
            saveToCache(CACHE_KEYS.PRODUCERS, newProducers);
            saveToCache(CACHE_KEYS.FECHAMENTOS, fechamentosData);
        } catch (error) {
            if (requestId === requestIdRef.current) {
                setNotification({ message: `Erro ao carregar dados: ${error.message}`, type: 'error' });
            }
        } finally {
            if (requestId === requestIdRef.current) {
                setIsLoading(false);
                setIsRefreshing(false);
            }
        }
    }, [apiService, token, setNotification, page, size]);

    // Fetch Pending Producers
    const fetchPendingProducers = useCallback(async () => {
        setIsLoadingPending(true);
        try {
            // Calculate start and end date of the selected month
            const inicio = new Date(pendingAno, pendingMes - 1, 1).toISOString().split('T')[0];
            const fim = new Date(pendingAno, pendingMes, 0).toISOString().split('T')[0];

            const data = await apiService.getProdutoresSemFechamento(token, inicio, fim);
            if (data) {
                setPendingProducers(data);
            } else {
                setPendingProducers([]);
            }
        } catch (error) {
            console.error('Erro ao buscar produtores pendentes:', error);
            setNotification({ message: 'Erro ao buscar produtores pendentes.', type: 'error' });
        } finally {
            setIsLoadingPending(false);
        }
    }, [apiService, token, pendingMes, pendingAno, setNotification]);

    // Só a PRIMEIRA carga mostra o spinner cheio; as demais (troca de página,
    // salvar) usam o indicador discreto de "atualizando".
    //
    // Passar `isLoading` aqui (como antes) seria um bug: fetchAllData chama
    // setIsLoading(true), o que mudaria o valor lido e re-dispararia este
    // efeito em loop. Um ref de primeira execução tem identidade estável e
    // expressa a mesma intenção sem depender de estado.
    const isFirstLoadRef = useRef(true);

    /**
     * D8: guarda de resposta obsoleta.
     *
     * fetchAllData depende de [page, size], então trocar de página dispara uma
     * carga nova sem esperar a anterior. `getAllProducers` percorre todas as
     * páginas no servidor, e a requisição antiga podia terminar DEPOIS da nova
     * e sobrescrever a tabela e o cache com dados da página errada. O id
     * incremental faz cada resposta validar que ainda é a mais recente antes de
     * aplicar. Também protege o setState pós-desmontagem no finally.
     */
    const requestIdRef = useRef(0);
    useEffect(() => {
        const first = isFirstLoadRef.current;
        isFirstLoadRef.current = false;
        fetchAllData(first);
    }, [fetchAllData]);

    // Fetch pending when tab or filters change
    useEffect(() => {
        if (activeTab === 'pendentes') {
            fetchPendingProducers();
        }
    }, [activeTab, pendingMes, pendingAno, fetchPendingProducers]);

    const availableLines = React.useMemo(() => [...new Set(producers.map(p => p.linha).filter(Boolean))].sort(), [producers]);
    const producerLineMap = React.useMemo(() => new Map(producers.map(p => [idTexto(p.id), p.linha])), [producers]);

    useEffect(() => {
        const originalTitle = document.title;

        const handleAfterPrint = () => {
            setReportData(null);
            setBulkReportData(null);
            document.title = originalTitle;
        };

        // Declarado fora do if para o cleanup conseguir cancelá-lo.
        let printTimeout = null;

        if (reportData || bulkReportData) {
            let filename = 'Documento';

            // Set custom title for filename
            if (reportData && reportData.fechamento) {
                const { produtorNome, mes, ano } = reportData.fechamento;
                const monthStr = mes.toString().padStart(2, '0');
                filename = `${produtorNome} - ${monthStr}-${ano}`;
                document.title = filename;
            } else if (bulkReportData) {
                const dateStr = new Date().toISOString().split('T')[0];
                filename = `Fechamentos - Lote - ${dateStr}`;
                document.title = filename;
            }

            // O listener precisa estar registrado ANTES do setTimeout, e não
            // dentro dele: o cleanup deste efeito já roda quando o timeout
            // dispara, de modo que um listener adicionado ali não era removido
            // por nada. Se o usuário cancelasse a impressão de fallback, o
            // 'afterprint' nunca chegava e o document.title (com o nome do
            // produtor) e o reportData ficavam presos na tela.
            window.addEventListener('afterprint', handleAfterPrint);

            // Check if running in Electron
            printTimeout = setTimeout(async () => {
                if (window.electronAPI) {
                    try {
                        let safeFilename = filename.endsWith('.pdf') ? filename : filename + '.pdf';
                        await window.electronAPI.saveToPdf(safeFilename);
                        handleAfterPrint(); // Diálogo de salvar concluído
                    } catch (error) {
                        console.error('Erro ao salvar PDF:', error);
                        // Fallback para impressão se o salvamento falhar. O
                        // listener já está registrado acima, então o
                        // cancelamento da impressão também limpa a tela.
                        window.print();
                    }
                } else {
                    window.print();
                }
            }, 500);
        }

        return () => {
            clearTimeout(printTimeout);
            window.removeEventListener('afterprint', handleAfterPrint);
            if (!reportData && !bulkReportData) {
                document.title = originalTitle;
            }
        };
    }, [reportData, bulkReportData]);

    // O modal precisa dos valores iniciais (produtor + período) quando é aberto
    // a partir da aba de pendentes. Antes esse valor vivia num state
    // `initialModalValues` que era ESCRITO mas nunca lido, e a prop
    // `initialValues` nem era passada ao modal — então o "Gerar Fechamento" da
    // aba de pendentes abria com o produtor e o período em branco. O modal já
    // recebia e consumia a prop; o que faltava era ligá-la.
    const [initialModalValues, setInitialModalValues] = useState(null);

    const handleOpenModal = (fechamento = null, initialValues = null) => {
        setEditingFechamento(fechamento);
        setInitialModalValues(initialValues);
        setIsModalOpen(true);
    };

    const handleCloseModal = () => {
        setIsModalOpen(false);
        setEditingFechamento(null);
        setInitialModalValues(null);
    };

    const handleSaveFechamento = async (data) => {
        try {
            const apiCall = editingFechamento
                ? apiService.atualizarPagamentoFechamento(token, editingFechamento.id, data)
                : apiService.createFechamento(token, data);

            await apiCall;
            setNotification({ message: `Fechamento ${editingFechamento ? 'atualizado' : 'gerado'} com sucesso!`, type: 'success' });
            handleCloseModal();
            fetchAllData();
        } catch (error) {
            const raw = error.message || '';
            const isDuplicate = raw.toLowerCase().includes('duplicate') ||
                raw.toLowerCase().includes('already exists') ||
                raw.toLowerCase().includes('já existe') ||
                raw.toLowerCase().includes('unique') ||
                raw.toLowerCase().includes('constraint');
            const friendlyMessage = isDuplicate
                ? 'Já existe um fechamento para este produtor neste período.'
                : (raw.startsWith('{') || raw.length > 200 ? 'Não foi possível salvar o fechamento.' : raw) || 'Não foi possível salvar.';
            setNotification({ message: `Erro: ${friendlyMessage}`, type: 'error' });
        }
    };

    const handleDelete = async (id) => {
        if (window.confirm('Tem certeza que deseja excluir este fechamento? Esta ação não pode ser desfeita.')) {
            try {
                await apiService.deleteFechamento(token, id);
                setNotification({ message: 'Fechamento excluído com sucesso.', type: 'success' });
                fetchAllData();
            } catch (error) {
                setNotification({ message: `Erro ao excluir: ${error.message}`, type: 'error' });
            }
        }
    };

    const handleMarkAsPaid = async (id) => {
        if (window.confirm('Marcar este fechamento como PAGO?')) {
            const fechamento = fechamentos.find(f => f.id === id);
            const data = {
                descontos: fechamento.descontos || 0,
                statusPagamento: 'Pago',
                dataPagamento: new Date().toISOString().split('T')[0]
            };
            try {
                await apiService.atualizarPagamentoFechamento(token, id, data);
                setNotification({ message: 'Fechamento marcado como pago.', type: 'success' });
                fetchAllData();
            } catch (error) {
                setNotification({ message: `Erro ao marcar como pago: ${error.message}`, type: 'error' });
            }
        }
    };

    const handlePrint = async (fechamento) => {
        try {
            // CORRIGIDO: Acessar produtorId diretamente
            // CORRIGIDO: Acessar produtorId diretamente e usar período
            const coletas = await apiService.getColetasPorPeriodo(token, fechamento.produtorId, fechamento.dataInicio, fechamento.dataFim);
            setReportData({ fechamento, coletas: coletas || [] });
        } catch (error) {
            setNotification({ message: error.message, type: 'error' });
        }
    };

    // Filter Logic
    const filteredFechamentos = fechamentos.filter(f => {
        const matchesSearch = f.produtorNome.toLowerCase().includes(searchTerm.toLowerCase());
        const matchesMonth = filterMonth ? f.mes === parseInt(filterMonth) : true;
        const matchesYear = filterYear ? f.ano === parseInt(filterYear) : true;
        const matchesStatus = filterStatus ? f.statusPagamento === filterStatus : true;
        const producerLine = producerLineMap.get(idTexto(f.produtorId));
        const matchesLine = filterLine ? producerLine === filterLine : true;

        return matchesSearch && matchesMonth && matchesYear && matchesStatus && matchesLine;
    }).sort((a, b) => {
        // Sort by dataInicio DESC (most recent first)
        const dateA = a.dataInicio || '';
        const dateB = b.dataInicio || '';
        return dateB.localeCompare(dateA);
    });

    const handleBulkPrint = async () => {
        if (filteredFechamentos.length === 0) {
            setNotification({ message: 'Não há fechamentos para imprimir.', type: 'error' });
            return;
        }
        setIsPrinting(true);
        try {
            const reports = await Promise.all(
                filteredFechamentos.map(async (fechamento) => {
                    // CORRIGIDO: Acessar produtorId diretamente e usar período
                    const coletas = await apiService.getColetasPorPeriodo(token, fechamento.produtorId, fechamento.dataInicio, fechamento.dataFim);
                    return { fechamento, coletas: coletas || [] };
                })
            );
            setBulkReportData(reports);
        } catch (error) {
            setNotification({ message: 'Ocorreu um erro ao preparar os relatórios para impressão.', type: 'error' });
        } finally {
            setIsPrinting(false);
        }
    };

    const formatCurrency = (value) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value || 0);
    const formatDate = (date) => date ? new Date(date).toLocaleDateString('pt-BR', { timeZone: 'UTC' }) : '—';


    return (
        <div className="main-content anim-pagina">
            <div className="flex justify-between items-center mb-6 no-print">
                <div className="flex items-center gap-3">
                    <h1 className="text-3xl font-bold text-gray-800">Fechamentos Mensais</h1>
                    {isRefreshing && (
                        <span className="text-sm text-gray-500 animate-pulse">Atualizando...</span>
                    )}
                </div>
                <div className="flex gap-4">
                    <button onClick={handleBulkPrint} className="btn-primary flex items-center gap-2" disabled={isPrinting}>
                        <PrintIcon />
                        {isPrinting ? 'Preparando...' : 'Imprimir Todos'}
                    </button>
                    <button onClick={() => handleOpenModal()} className="btn-primary flex items-center gap-2">
                        <PlusIcon />
                        Gerar Fechamento
                    </button>
                </div>
            </div>

            {/* Tabs Navigation */}
            <div className="flex border-b border-gray-200 mb-6 no-print">
                <button
                    className={`py-2 px-4 font-semibold text-sm transition-colors ${activeTab === 'historico'
                        ? 'text-blue-600 border-b-2 border-blue-600'
                        : 'text-gray-500 hover:text-gray-700'
                        }`}
                    onClick={() => setActiveTab('historico')}
                >
                    Histórico de Fechamentos
                </button>
                <button
                    className={`py-2 px-4 font-semibold text-sm transition-colors ${activeTab === 'pendentes'
                        ? 'text-blue-600 border-b-2 border-blue-600'
                        : 'text-gray-500 hover:text-gray-700'
                        }`}
                    onClick={() => setActiveTab('pendentes')}
                >
                    Produtores Pendentes
                </button>
            </div>

            {/* TAB: PENDENTES */}
            {activeTab === 'pendentes' && (
                <>
                    <div className="bg-white p-4 rounded-xl shadow-sm mb-6 flex gap-4 items-center no-print">
                        <div className="flex items-center gap-2">
                            <label className="text-sm font-semibold text-gray-700">Mês:</label>
                            <select
                                value={pendingMes}
                                onChange={(e) => setPendingMes(parseInt(e.target.value))}
                                className="input-field"
                            >
                                {months.map(m => (
                                    <option key={m.value} value={m.value}>{m.label}</option>
                                ))}
                            </select>
                        </div>
                        <div className="flex items-center gap-2">
                            <label className="text-sm font-semibold text-gray-700">Ano:</label>
                            <select
                                value={pendingAno}
                                onChange={(e) => setPendingAno(parseInt(e.target.value))}
                                className="input-field"
                            >
                                {years.map(y => (
                                    <option key={y} value={y}>{y}</option>
                                ))}
                            </select>
                        </div>
                        <button
                            onClick={fetchPendingProducers}
                            className="btn-secondary text-sm ml-auto"
                        >
                            Atualizar Lista
                        </button>
                    </div>

                    <div className="bg-white rounded-xl shadow-md overflow-hidden no-print">
                        {isLoadingPending ? (
                            <p className="p-6 text-center text-gray-500">Buscando produtores pendentes...</p>
                        ) : pendingProducers.length === 0 ? (
                            <p className="p-6 text-center text-gray-500">Todos os produtores que têm coletas nesse mês possuem fechamento.</p>
                        ) : (
                            <table className="min-w-full divide-y divide-gray-200">
                                <thead className="bg-gray-50">
                                    <tr>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Produtor</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Linha/Região</th>
                                        <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Ação</th>
                                    </tr>
                                </thead>
                                <tbody className="bg-white divide-y divide-gray-200">
                                    {pendingProducers.map((produtor) => (
                                        <tr key={produtor.id} className="hover:bg-gray-50">
                                            <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{produtor.nome}</td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{produtor.linha || '-'}</td>
                                            <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                                                <button
                                                    onClick={() => {
                                                        const firstDay = new Date(pendingAno, pendingMes - 1, 1).toISOString().split('T')[0];
                                                        const lastDay = new Date(pendingAno, pendingMes, 0).toISOString().split('T')[0];
                                                        handleOpenModal(null, {
                                                            produtorId: idTexto(produtor.id),
                                                            dataInicio: firstDay,
                                                            dataFim: lastDay
                                                        });
                                                    }}
                                                    className="text-blue-600 hover:text-blue-900 font-semibold"
                                                >
                                                    Gerar Fechamento
                                                </button>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </div>
                </>
            )}

            {/* TAB: HISTORICO (Existing Layout wrapped in conditional) */}
            {activeTab === 'historico' && (
                <>
                    <div className="bg-white p-4 rounded-xl shadow-sm mb-6 grid grid-cols-1 md:grid-cols-4 gap-4 no-print">
                        <input
                            type="text"
                            placeholder="Buscar produtor..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            className="input-field"
                        />
                        <select value={filterLine} onChange={(e) => setFilterLine(e.target.value)} className="input-field">
                            <option value="">Todas as Linhas</option>
                            {availableLines.map(line => <option key={line} value={line}>{line}</option>)}
                        </select>
                        <select value={filterMonth} onChange={(e) => setFilterMonth(e.target.value)} className="input-field">
                            <option value="">Todos os Meses</option>
                            {Array.from({ length: 12 }, (_, i) => i + 1).map(m => (
                                <option key={m} value={m}>{new Date(0, m - 1).toLocaleString('pt-BR', { month: 'long' })}</option>
                            ))}
                        </select>
                        <select value={filterYear} onChange={(e) => setFilterYear(e.target.value)} className="input-field">
                            <option value="">Todos os Anos</option>
                            {/* Reaproveita a mesma lista `years` do formulário (ano
                                corrente + 1 até −5). Estava com 2024/2025 fixos
                                aqui, o que deixava 2026 — o ano corrente — impossível
                                de filtrar. */}
                            {years.map(y => <option key={y} value={y}>{y}</option>)}
                        </select>
                        <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)} className="input-field">
                            <option value="">Todos Status</option>
                            <option value="Pendente">Pendente</option>
                            <option value="Pago">Pago</option>
                        </select>
                    </div>

                    <div className="bg-white rounded-xl shadow-md overflow-x-auto no-print">
                        {isLoading ? (
                            <p className="p-6 text-center text-gray-500">Carregando...</p>
                        ) : (
                            <table className="min-w-full divide-y divide-gray-200">
                                <thead className="bg-gray-50">
                                    <tr>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Produtor</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Período</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Total Litros</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Total Líquido</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Pagamento</th>
                                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Data Pag.</th>
                                        <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Ações</th>
                                    </tr>
                                </thead>
                                <tbody className="bg-white divide-y divide-gray-200">
                                    {filteredFechamentos.map((f) => (
                                        <tr key={f.id} className="hover:bg-gray-50">
                                            <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                                                {f.produtorNome || 'N/A'}
                                                {f.estrategiaPreco === 'Qualidade' && (
                                                    <span className="ml-2 inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800">
                                                        Qualidade
                                                    </span>
                                                )}
                                            </td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                                {formatDate(f.dataInicio)} - {formatDate(f.dataFim)}
                                            </td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{f.totalLitros} L</td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm font-bold text-gray-800">{formatCurrency(f.totalLiquido)}</td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm">
                                                <span className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full ${f.statusPagamento === 'Pago' ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'}`}>
                                                    {f.statusPagamento}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{formatDate(f.dataPagamento)}</td>
                                            <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                                                <div className="flex justify-end gap-4">
                                                    <button onClick={() => handlePrint(f)} title="Imprimir" className="text-gray-600 hover:text-gray-900"><PrintIcon /></button>
                                                    {f.statusPagamento !== 'Pago' && (
                                                        <button onClick={() => handleMarkAsPaid(f.id)} title="Marcar como Pago" className="text-green-600 hover:text-green-900"><DollarSignIcon /></button>
                                                    )}
                                                    <button onClick={() => handleOpenModal(f)} title="Editar" className="text-blue-600 hover:text-blue-900"><EditIcon /></button>
                                                    <button onClick={() => handleDelete(f.id)} title="Excluir" className="text-red-600 hover:text-red-900"><TrashIcon /></button>
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}

                        {/* Pagination Controls */}
                        {!isLoading && totalPages > 1 && (
                            <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between no-print">
                                <div className="text-sm text-gray-700">
                                    Mostrando <span className="font-medium">{page * size + 1}</span> até <span className="font-medium">{Math.min((page + 1) * size, totalElements)}</span> de <span className="font-medium">{totalElements}</span> resultados
                                </div>
                                <div className="flex gap-2">
                                    <button
                                        onClick={() => setPage(prev => Math.max(0, prev - 1))}
                                        disabled={page === 0}
                                        className={`px-4 py-2 border rounded-lg text-sm font-medium transition-colors ${page === 0 ? 'bg-gray-100 text-gray-400 cursor-not-allowed' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
                                    >
                                        Anterior
                                    </button>
                                    <div className="flex items-center px-4">
                                        <span className="text-sm text-gray-700">Página <span className="font-medium">{page + 1}</span> de <span className="font-medium">{totalPages}</span></span>
                                    </div>
                                    <button
                                        onClick={() => setPage(prev => Math.min(totalPages - 1, prev + 1))}
                                        disabled={page === totalPages - 1}
                                        className={`px-4 py-2 border rounded-lg text-sm font-medium transition-colors ${page === totalPages - 1 ? 'bg-gray-100 text-gray-400 cursor-not-allowed' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
                                    >
                                        Próxima
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                </>
            )}

            <FechamentoModal
                isOpen={isModalOpen}
                onClose={handleCloseModal}
                onSave={handleSaveFechamento}
                producers={producers}
                fechamento={editingFechamento}
                initialValues={initialModalValues}
                apiService={apiService}
                token={token}
            />

            <div className="printable-area">
                {reportData && <PrintableReport reportData={reportData} />}
                {bulkReportData && <PrintableBulkReport bulkReportData={bulkReportData} />}
            </div>
        </div>
    );
};

export default FechamentoPage;