// src/pages/ProdutorPage.js
import React, { useState, useEffect, useCallback } from 'react';

// --- Ícones ---
const EditIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" /><path d="m15 5 4 4" /></svg>
);

const TrashIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" /><path d="M10 11v6" /><path d="M14 11v6" /><path d="M5 6l1.4-2.8A2 2 0 0 1 8.2 2h7.6a2 2 0 0 1 1.8 1.2L19 6" /></svg>
);

const PlusIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
);

const FilterIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon></svg>
);


// --- Componente do Modal ---
const ProducerModal = ({ isOpen, onClose, onSave, producer }) => {
    const [formData, setFormData] = useState({ nome: '', linha: '', chavePix: '', inscricaoEstadual: '', ativo: true, usaPagamentoPorQualidade: false });

    useEffect(() => {
        if (producer) {
            setFormData({
                nome: producer.nome || '',
                linha: producer.linha || '',
                chavePix: producer.chavePix || '',
                inscricaoEstadual: producer.inscricaoEstadual || '',
                ativo: producer.ativo !== undefined ? producer.ativo : true,
                usaPagamentoPorQualidade: producer.usaPagamentoPorQualidade !== undefined ? producer.usaPagamentoPorQualidade : false,
            });
        } else {
            setFormData({ nome: '', linha: '', chavePix: '', inscricaoEstadual: '', ativo: true, usaPagamentoPorQualidade: false });
        }
    }, [producer, isOpen]);

    const handleChange = (e) => {
        const { name, value, type, checked } = e.target;
        setFormData(prev => ({ ...prev, [name]: type === 'checkbox' ? checked : value }));
    };

    const handleSubmit = (e) => {
        e.preventDefault();
        onSave(formData);
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
            <div className="bg-white p-8 rounded-xl shadow-lg w-full max-w-md">
                <h2 className="text-2xl font-bold mb-6">{producer ? 'Editar Produtor' : 'Adicionar Produtor'}</h2>
                <form onSubmit={handleSubmit}>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2">Nome</label>
                        <input name="nome" value={formData.nome} onChange={handleChange} className="input-field" required />
                    </div>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2">Linha/Região</label>
                        <input name="linha" value={formData.linha} onChange={handleChange} className="input-field" />
                    </div>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2">Chave PIX</label>
                        <input name="chavePix" value={formData.chavePix} onChange={handleChange} className="input-field" />
                    </div>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2">Inscrição Estadual (IE)</label>
                        <input name="inscricaoEstadual" value={formData.inscricaoEstadual} onChange={handleChange} className="input-field" placeholder="Ex: 123456789" />
                    </div>
                    <div className="mb-4 flex items-center">
                        <input type="checkbox" name="usaPagamentoPorQualidade" id="usaPagamentoPorQualidade" checked={formData.usaPagamentoPorQualidade} onChange={handleChange} className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                        <label htmlFor="usaPagamentoPorQualidade" className="ml-2 block text-sm text-gray-900">Usar Pagamento por Qualidade</label>
                    </div>
                    <div className="mb-6 flex items-center">
                        <input type="checkbox" name="ativo" id="ativo" checked={formData.ativo} onChange={handleChange} className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                        <label htmlFor="ativo" className="ml-2 block text-sm text-gray-900">Ativo</label>
                    </div>
                    <div className="flex justify-end gap-4">
                        <button type="button" onClick={onClose} className="py-2 px-5 bg-gray-200 text-gray-800 font-semibold rounded-lg hover:bg-gray-300 transition-colors">Cancelar</button>
                        <button type="submit" className="btn-primary">Salvar</button>
                    </div>
                </form>
            </div>
        </div>
    );
};


// --- Página Principal de Produtores ---
const ProdutorPage = ({ apiService, token, setNotification }) => {
    const [producers, setProducers] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [editingProducer, setEditingProducer] = useState(null);

    const [searchTerm, setSearchTerm] = useState('');
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({ status: '', linha: '' });

    // Pagination State
    const [page, setPage] = useState(0);
    const [size] = useState(20);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    /**
     * Lista completa de produtores, só para a busca.
     *
     * A tabela é paginada no servidor (20 por página), mas a busca e o filtro
     * de linha eram aplicados sobre a página corrente: quem estivesse na
     * página 3 era "invisible" para a busca e a resposta era "nenhum
     * resultado". A lista completa é pequena (dezenas de produtores) e é o que
     * a busca realmente precisa.
     */
    const [todosProdutores, setTodosProdutores] = useState([]);

    const fetchProducers = useCallback(async () => {
        setIsLoading(true);
        try {
            const data = await apiService.getProducers(token, { page, size, sortBy: 'nome', sortDir: 'asc' });
            if (data && data.content) {
                setProducers(data.content);
                setTotalPages(data.totalPages);
                setTotalElements(data.totalElements);
            } else {
                setProducers([]);
                setTotalPages(0);
                setTotalElements(0);
            }
        } catch (error) {
            setNotification({ message: `Erro de conexão ao carregar produtores: ${error.message}`, type: 'error' });
        } finally {
            setIsLoading(false);
        }
    }, [apiService, token, setNotification, page, size]);

    useEffect(() => {
        fetchProducers();
    }, [fetchProducers]);

    useEffect(() => {
        let cancelled = false;
        apiService.getAllProducers(token)
            .then((lista) => { if (!cancelled) setTodosProdutores(lista || []); })
            .catch(() => { if (!cancelled) setTodosProdutores([]); });
        return () => { cancelled = true; };
    }, [token, apiService]);

    const handleOpenModal = (producer = null) => {
        setEditingProducer(producer);
        setIsModalOpen(true);
    };

    const handleCloseModal = () => {
        setIsModalOpen(false);
        setEditingProducer(null);
    };

    const handleSaveProducer = async (producerData) => {
        // Prepare payload with both casing conventions to ensure backend compatibility
        const payload = {
            ...producerData,
            usaPagamentoPorQualidade: producerData.usaPagamentoPorQualidade,
            usa_pagamento_por_qualidade: producerData.usaPagamentoPorQualidade // Defensive: send snake_case too
        };

        if (editingProducer) {
            payload.id = editingProducer.id;
        }

        try {
            const apiCall = editingProducer
                ? apiService.updateProducer(token, payload.id, payload)
                : apiService.createProducer(token, payload);

            await apiCall;

            setNotification({ message: `Produtor ${editingProducer ? 'atualizado' : 'criado'} com sucesso!`, type: 'success' });
            handleCloseModal();
            fetchProducers(); // Recarrega a lista
        } catch (error) {
            setNotification({ message: `Erro ao salvar: ${error.message}`, type: 'error' });
        }
    };

    const handleDeleteProducer = async (id) => {
        if (window.confirm('Tem certeza que deseja excluir este produtor?')) {
            try {
                await apiService.deleteProducer(token, id);
                setNotification({ message: 'Produtor excluído com sucesso!', type: 'success' });
                fetchProducers(); // Recarrega a lista
            } catch (error) {
                setNotification({ message: `Erro ao excluir: ${error.message}`, type: 'error' });
            }
        }
    };
    // A busca e os filtros incidem sobre a lista COMPLETA. Sobre a página
    // corrente, o resultado era enganoso: buscar um produtor que estivesse
    // na página 3 respondia "nenhum resultado".
    const baseDaBusca = (todosProdutores.length > 0 ? todosProdutores : producers);
    const searching = searchTerm !== '' || filters.status !== '' || filters.linha !== '';

    // Sem busca/filtro ativo, a lista paginada do servidor é a exibida (com os
    // controles de página). Com busca ativa, os resultados vêm da lista
    // completa — paginação de servidor deixaria de fazer sentido.
    const filteredProducers = baseDaBusca.filter(producer => {
        const matchesSearch = producer.nome.toLowerCase().includes(searchTerm.toLowerCase()) ||
            (producer.linha && producer.linha.toLowerCase().includes(searchTerm.toLowerCase()));

        const matchesStatus = filters.status === '' ? true :
            filters.status === 'ativo' ? producer.ativo :
                filters.status === 'inativo' ? !producer.ativo : true;

        const matchesLinha = filters.linha === '' ? true : producer.linha === filters.linha;

        return matchesSearch && matchesStatus && matchesLinha;
    });

    // Mudar o filtro volta para a primeira página: sem isso, o usuário
    // filtrando na página 3 veria a página 3 do resultado novo, ou vazio.
    useEffect(() => {
        setPage(0);
    }, [searchTerm, filters.status, filters.linha]);

    const uniqueLines = [...new Set(baseDaBusca.map(p => p.linha).filter(Boolean))].sort();


    return (
        <div className="anim-pagina">
            <div className="flex flex-col md:flex-row justify-between items-center mb-6 gap-4">
                <h1 className="text-3xl font-bold text-gray-800">Gestão de Produtores</h1>
                <div className="flex gap-4 w-full md:w-auto">
                    <input
                        type="text"
                        placeholder="Buscar por nome ou linha..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                        className="input-field w-full md:w-64"
                    />
                    <button
                        onClick={() => setShowFilters(!showFilters)}
                        className={`p-2 border rounded-lg hover:bg-gray-100 transition-colors ${showFilters ? 'bg-blue-50 border-blue-200 text-blue-600' : 'border-gray-300 text-gray-600'}`}
                        title="Filtros"
                    >
                        <FilterIcon />
                    </button>
                    <button onClick={() => handleOpenModal()} className="btn-primary flex items-center gap-2 whitespace-nowrap">
                        <PlusIcon />
                        Adicionar
                    </button>
                </div>
            </div>

            {/* Filter Panel */}
            {showFilters && (
                <div className="bg-white p-4 rounded-xl shadow-sm mb-6 border border-gray-100 grid grid-cols-1 md:grid-cols-3 gap-4 animate-fadeIn">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">Status</label>
                        <select
                            value={filters.status}
                            onChange={(e) => setFilters(prev => ({ ...prev, status: e.target.value }))}
                            className="input-field w-full"
                        >
                            <option value="">Todos</option>
                            <option value="ativo">Ativo</option>
                            <option value="inativo">Inativo</option>
                        </select>
                    </div>
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">Linha/Região</label>
                        <select
                            value={filters.linha}
                            onChange={(e) => setFilters(prev => ({ ...prev, linha: e.target.value }))}
                            className="input-field w-full"
                        >
                            <option value="">Todas</option>
                            {uniqueLines.map(line => (
                                <option key={line} value={line}>{line}</option>
                            ))}
                        </select>
                    </div>
                    <div className="flex items-end">
                        <button
                            onClick={() => setFilters({ status: '', linha: '' })}
                            className="text-sm text-gray-500 hover:text-gray-700 underline mb-3"
                        >
                            Limpar Filtros
                        </button>
                    </div>
                </div>
            )}

            <div className="bg-white rounded-xl shadow-md overflow-hidden">
                {isLoading ? (
                    <p className="p-6 text-center text-gray-500">Carregando produtores...</p>
                ) : (
                    <table className="min-w-full divide-y divide-gray-200">
                        <thead className="bg-gray-50">
                            <tr>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Nome</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Linha/Região</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Chave PIX</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                                <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Ações</th>
                            </tr>
                        </thead>
                        <tbody className="bg-white divide-y divide-gray-200">

                            {filteredProducers.map((producer) => (
                                <tr key={producer.id} className="hover:bg-gray-50">
                                    <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{producer.nome}</td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{producer.linha}</td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{producer.chavePix}</td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm">
                                        <div className="flex flex-col gap-1">
                                            <span className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full w-fit ${producer.ativo ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'}`}>
                                                {producer.ativo ? 'Ativo' : 'Inativo'}
                                            </span>
                                            {producer.usaPagamentoPorQualidade && (
                                                <span className="px-2 inline-flex text-xs leading-5 font-semibold rounded-full w-fit bg-blue-100 text-blue-800">
                                                    Qualidade
                                                </span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                                        <div className="flex justify-end gap-4">
                                            <button onClick={() => handleOpenModal(producer)} className="text-blue-600 hover:text-blue-900" title="Editar"><EditIcon /></button>
                                            <button onClick={() => handleDeleteProducer(producer.id)} className="text-red-600 hover:text-red-900" title="Excluir"><TrashIcon /></button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}

                {/* Controles de página só valem sem busca/filtro ativo: com
                    busca, os resultados vêm da lista completa e a paginação de
                    servidor não se aplica. */}
                {!isLoading && !searching && totalPages > 1 && (
                    <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
                        <div className="text-sm text-gray-700">
                            {searching ? (
                                <>{filteredProducers.length} resultado{filteredProducers.length === 1 ? '' : 's'} para o filtro atual</>
                            ) : (
                                <>Mostrando <span className="font-medium">{page * size + 1}</span> até <span className="font-medium">{Math.min((page + 1) * size, totalElements)}</span> de <span className="font-medium">{totalElements}</span> resultados</>
                            )}
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
            <ProducerModal isOpen={isModalOpen} onClose={handleCloseModal} onSave={handleSaveProducer} producer={editingProducer} />
        </div>
    );
};

export default ProdutorPage;