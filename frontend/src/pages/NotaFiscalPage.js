import React, { useState, useEffect } from 'react';
import { idTexto, mesmoId } from '../utils/ids';

// --- Icons ---
const DownloadIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" />
    </svg>
);

const SpinnerIcon = () => (
    <svg className="animate-spin h-5 w-5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
    </svg>
);

// --- Searchable Producer Select (simplified version) ---
const SearchableProducerSelect = ({ producers, selectedProducerId, onChange, disabled }) => {
    const [searchTerm, setSearchTerm] = useState('');
    const [isOpen, setIsOpen] = useState(false);
    const inputRef = React.useRef(null);

    const filteredProducers = React.useMemo(() => {
        return producers.filter(p =>
            p.nome.toLowerCase().includes(searchTerm.toLowerCase())
        );
    }, [producers, searchTerm]);

    const selectedProducer = producers.find(p => mesmoId(p.id, selectedProducerId));
    const displayValue = selectedProducer ? selectedProducer.nome : '';

    const handleSelectProducer = (producerId) => {
        onChange(producerId);
        setSearchTerm('');
        setIsOpen(false);
    };

    const handleBlur = () => {
        setTimeout(() => {
            setIsOpen(false);
            setSearchTerm('');
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
            <input
                ref={inputRef}
                type="text"
                value={isOpen ? searchTerm : displayValue}
                onChange={(e) => { setSearchTerm(e.target.value); setIsOpen(true); }}
                onFocus={() => setIsOpen(true)}
                onBlur={handleBlur}
                placeholder="Selecione um produtor..."
                className="input-field w-full"
            />
            {isOpen && (
                <ul className="absolute z-50 w-full mt-1 max-h-60 overflow-auto bg-white border border-gray-300 rounded-lg shadow-lg">
                    {filteredProducers.length === 0 ? (
                        <li className="px-3 py-2 text-gray-500 italic">Nenhum produtor encontrado</li>
                    ) : (
                        filteredProducers.map((producer) => (
                            <li
                                key={producer.id}
                                onClick={() => handleSelectProducer(producer.id)}
                                className={`px-3 py-2 cursor-pointer hover:bg-gray-100 ${mesmoId(producer.id, selectedProducerId) ? 'font-semibold bg-blue-50' : ''}`}
                            >
                                {producer.nome}
                                {producer.inscricaoEstadual && (
                                    <span className="ml-2 text-xs text-gray-500">IE: {producer.inscricaoEstadual}</span>
                                )}
                            </li>
                        ))
                    )}
                </ul>
            )}
        </div>
    );
};

// --- Component ---
const NotaFiscalPage = ({ apiService, token, setNotification }) => {
    const [producers, setProducers] = useState([]);
    const [selectedProducerId, setSelectedProducerId] = useState('');
    const [ano, setAno] = useState(new Date().getFullYear());
    const [mes, setMes] = useState(new Date().getMonth() + 1);
    const [usarDadosSimulados, setUsarDadosSimulados] = useState(false);
    const [quantidadeDiasColeta, setQuantidadeDiasColeta] = useState(15);
    const [valorMinimo, setValorMinimo] = useState(300);
    const [valorMaximo, setValorMaximo] = useState(400);
    const [generatedData, setGeneratedData] = useState([]);
    const [isLoading, setIsLoading] = useState(false);
    const [isExporting, setIsExporting] = useState(false);

    // Fetch producers on mount
    useEffect(() => {
        const fetchProducers = async () => {
            try {
                // Lista completa (paginação transparente): pedir size=1000 seria
                // clampado em 200 pelo backend e o seletor de produtor ficaria
                // incompleto.
                setProducers(await apiService.getAllProducers(token));
            } catch (error) {
                console.error("Erro ao carregar produtores", error);
                setNotification({ message: 'Erro ao carregar lista de produtores.', type: 'error' });
            }
        };
        fetchProducers();
    }, [apiService, token, setNotification]);

    const handleGenerate = async () => {
        if (!selectedProducerId) {
            setNotification({ message: 'Selecione um produtor.', type: 'error' });
            return;
        }

        setIsLoading(true);
        setGeneratedData([]);

        try {
            const request = {
                produtorId: idTexto(selectedProducerId),
                ano: parseInt(ano),
                mes: parseInt(mes),
                usarDadosSimulados,
                ...(usarDadosSimulados && {
                    quantidadeDiasColeta: parseInt(quantidadeDiasColeta),
                    valorMinimo: parseInt(valorMinimo),
                    valorMaximo: parseInt(valorMaximo),
                }),
            };

            const result = await apiService.gerarNotaFiscal(token, request);
            setGeneratedData(result || []);

            if (!result || result.length === 0) {
                setNotification({ message: 'Nenhum dado encontrado para o período.', type: 'info' });
            } else {
                setNotification({ message: `${result.length} registros gerados com sucesso!`, type: 'success' });
            }
        } catch (error) {
            console.error(error);
            setNotification({ message: 'Erro ao gerar dados: ' + error.message, type: 'error' });
        } finally {
            setIsLoading(false);
        }
    };

    const handleExport = async () => {
        if (generatedData.length === 0) {
            setNotification({ message: 'Gere os dados primeiro antes de exportar.', type: 'error' });
            return;
        }

        setIsExporting(true);

        try {
            const request = {
                produtorId: idTexto(selectedProducerId),
                ano: parseInt(ano),
                mes: parseInt(mes),
                usarDadosSimulados,
                ...(usarDadosSimulados && {
                    quantidadeDiasColeta: parseInt(quantidadeDiasColeta),
                    valorMinimo: parseInt(valorMinimo),
                    valorMaximo: parseInt(valorMaximo),
                }),
            };

            const blob = await apiService.exportarNotaFiscalXlsx(token, request);

            // Create download link
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `nota_fiscal_${ano}_${mes.toString().padStart(2, '0')}.xlsx`;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);

            setNotification({ message: 'Arquivo XLSX exportado com sucesso!', type: 'success' });
        } catch (error) {
            console.error(error);
            setNotification({ message: 'Erro ao exportar: ' + error.message, type: 'error' });
        } finally {
            setIsExporting(false);
        }
    };

    const selectedProducer = producers.find(p => mesmoId(p.id, selectedProducerId));

    return (
        <div className="max-w-6xl mx-auto anim-pagina">
            <h1 className="text-3xl font-bold text-gray-800 mb-8">Gerar Dados para Nota Fiscal</h1>

            {/* Configuration Card */}
            <div className="bg-white p-6 rounded-xl shadow-md mb-6">
                <h2 className="text-xl font-semibold text-gray-700 mb-4">Configurações</h2>

                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                    {/* Producer Selector */}
                    <div className="lg:col-span-2">
                        <label className="block text-gray-600 text-sm mb-1">Produtor</label>
                        <SearchableProducerSelect
                            producers={producers}
                            selectedProducerId={selectedProducerId}
                            onChange={setSelectedProducerId}
                        />
                        {selectedProducer && !selectedProducer.inscricaoEstadual && (
                            <p className="text-xs text-amber-600 mt-1">
                                ⚠️ Este produtor não possui Inscrição Estadual cadastrada.
                            </p>
                        )}
                    </div>

                    {/* Month/Year */}
                    <div className="flex gap-2">
                        <div className="flex-1">
                            <label className="block text-gray-600 text-sm mb-1">Mês</label>
                            <select
                                value={mes}
                                onChange={(e) => setMes(parseInt(e.target.value))}
                                className="input-field"
                            >
                                {[...Array(12)].map((_, i) => (
                                    <option key={i + 1} value={i + 1}>
                                        {new Date(2000, i, 1).toLocaleDateString('pt-BR', { month: 'long' })}
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="w-24">
                            <label className="block text-gray-600 text-sm mb-1">Ano</label>
                            <input
                                type="number"
                                value={ano}
                                onChange={(e) => setAno(e.target.value)}
                                className="input-field"
                                min="2020"
                                max="2030"
                            />
                        </div>
                    </div>
                </div>

                {/* Data Type Toggle */}
                <div className="mt-6">
                    <label className="block text-gray-600 text-sm mb-2">Tipo de Dados</label>
                    <div className="flex items-center bg-gray-100 rounded-full p-1 w-fit">
                        <button
                            onClick={() => setUsarDadosSimulados(false)}
                            className={`px-4 py-2 rounded-full text-sm font-medium transition-colors duration-200 ${!usarDadosSimulados
                                    ? 'bg-blue-600 text-white shadow-sm'
                                    : 'text-gray-600 hover:text-gray-800'
                                }`}
                        >
                            Dados Reais
                        </button>
                        <button
                            onClick={() => setUsarDadosSimulados(true)}
                            className={`px-4 py-2 rounded-full text-sm font-medium transition-colors duration-200 ${usarDadosSimulados
                                    ? 'bg-blue-600 text-white shadow-sm'
                                    : 'text-gray-600 hover:text-gray-800'
                                }`}
                        >
                            Dados Simulados
                        </button>
                    </div>
                </div>

                {/* Simulation Options */}
                {usarDadosSimulados && (
                    <div className="mt-4 p-4 bg-blue-50 rounded-lg border border-blue-100">
                        <h3 className="text-sm font-semibold text-blue-800 mb-3">Opções de Simulação</h3>
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                            <div>
                                <label className="block text-xs text-gray-600 mb-1">Dias de Coleta</label>
                                <input
                                    type="number"
                                    value={quantidadeDiasColeta}
                                    onChange={(e) => setQuantidadeDiasColeta(e.target.value)}
                                    className="input-field text-sm"
                                    min="1"
                                    max="31"
                                />
                            </div>
                            <div>
                                <label className="block text-xs text-gray-600 mb-1">Valor Mínimo (L)</label>
                                <input
                                    type="number"
                                    value={valorMinimo}
                                    onChange={(e) => setValorMinimo(e.target.value)}
                                    className="input-field text-sm"
                                    min="0"
                                />
                            </div>
                            <div>
                                <label className="block text-xs text-gray-600 mb-1">Valor Máximo (L)</label>
                                <input
                                    type="number"
                                    value={valorMaximo}
                                    onChange={(e) => setValorMaximo(e.target.value)}
                                    className="input-field text-sm"
                                    min="0"
                                />
                            </div>
                        </div>
                    </div>
                )}

                {/* Generate Button */}
                <div className="mt-6">
                    <button
                        onClick={handleGenerate}
                        disabled={isLoading || !selectedProducerId}
                        className="btn-primary flex items-center gap-2"
                    >
                        {isLoading ? (
                            <>
                                <SpinnerIcon />
                                Gerando...
                            </>
                        ) : (
                            'Gerar Dados'
                        )}
                    </button>
                </div>
            </div>

            {/* Data Preview */}
            {generatedData.length > 0 && (
                <div className="bg-white p-6 rounded-xl shadow-md">
                    <div className="flex justify-between items-center mb-4">
                        <h2 className="text-xl font-semibold text-gray-700">
                            Dados Gerados ({generatedData.length} registros)
                        </h2>
                        <button
                            onClick={handleExport}
                            disabled={isExporting}
                            className="btn-primary flex items-center gap-2"
                        >
                            {isExporting ? (
                                <>
                                    <SpinnerIcon />
                                    Exportando...
                                </>
                            ) : (
                                <>
                                    <DownloadIcon />
                                    Exportar XLSX
                                </>
                            )}
                        </button>
                    </div>

                    <div className="overflow-x-auto">
                        <table className="min-w-full divide-y divide-gray-200 border">
                            <thead className="bg-gray-50">
                                <tr>
                                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        CD_PRODUTOR_IE
                                    </th>
                                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        DT_RECEBIMENTO
                                    </th>
                                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        QT_LITROS
                                    </th>
                                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        CD_PLACA
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="bg-white divide-y divide-gray-200">
                                {generatedData.map((row, idx) => (
                                    <tr key={idx} className="hover:bg-gray-50">
                                        <td className="px-4 py-2 whitespace-nowrap text-sm text-gray-900">
                                            {row.cdProdutorIe || '-'}
                                        </td>
                                        <td className="px-4 py-2 whitespace-nowrap text-sm text-gray-600">
                                            {row.dtRecebimento}
                                        </td>
                                        <td className="px-4 py-2 whitespace-nowrap text-sm text-gray-900 text-right font-medium">
                                            {row.qtLitros}
                                        </td>
                                        <td className="px-4 py-2 whitespace-nowrap text-sm text-gray-600">
                                            {row.cdPlaca}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                            <tfoot className="bg-gray-100">
                                <tr>
                                    <td colSpan="2" className="px-4 py-2 text-sm font-semibold text-gray-700">
                                        Total
                                    </td>
                                    <td className="px-4 py-2 text-sm font-bold text-gray-900 text-right">
                                        {generatedData.reduce((acc, row) => acc + (row.qtLitros || 0), 0).toLocaleString('pt-BR')} L
                                    </td>
                                    <td></td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
};

export default NotaFiscalPage;
