import React, { useState, useCallback, useEffect } from 'react';
import { useDropzone } from 'react-dropzone';
import ProducerAutocomplete from '../components/ProducerAutocomplete';
import { formatRetryAfter } from '../services/apiService';
import { idTexto } from '../utils/ids';
// apiService is passed as prop


// --- Icons ---
const UploadIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-12 w-12 text-gray-400" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
    </svg>
);

const CheckIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
    </svg>
);

// --- Component ---
const ImportacaoColetasPage = ({ apiService, token, setNotification }) => {
    const [uploadStatus, setUploadStatus] = useState('IDLE'); // IDLE, UPLOADING, REVIEW, SAVING, SUCCESS, ERROR
    const [extractedData, setExtractedData] = useState([]); // Matrix format loaded here
    const [daysInPeriod, setDaysInPeriod] = useState([]); // Headers for days
    const [producers, setProducers] = useState([]);
    const [ocrService, setOcrService] = useState('gemini'); // 'gemini' or 'azure'
    const [pendingCreateCount, setPendingCreateCount] = useState(0); // in-flight inline producer creations

    // Referência (ano/mês) das coletas na planilha being importada. O OCR só
    // enxerga o dia da coluna; o ano/mês vêm daqui. Sem informar, o backend
    // assume "mês passado" e uma planilha antiga seria importada com datas
    // erradas.
    const [periodoMes, setPeriodoMes] = useState(() => {
        const lastMonth = new Date();
        lastMonth.setDate(1);
        lastMonth.setMonth(lastMonth.getMonth() - 1);
        return String(lastMonth.getMonth() + 1).padStart(2, '0');
    });
    const [periodoAno, setPeriodoAno] = useState(() => String(new Date().getFullYear()));

    const mesesOptions = [
        { value: '01', label: 'Janeiro' }, { value: '02', label: 'Fevereiro' },
        { value: '03', label: 'Março' }, { value: '04', label: 'Abril' },
        { value: '05', label: 'Maio' }, { value: '06', label: 'Junho' },
        { value: '07', label: 'Julho' }, { value: '08', label: 'Agosto' },
        { value: '09', label: 'Setembro' }, { value: '10', label: 'Outubro' },
        { value: '11', label: 'Novembro' }, { value: '12', label: 'Dezembro' },
    ];

    const anosOptions = Array.from({ length: 6 }, (_, i) => String(new Date().getFullYear() - i + 1));

    // Fetch producers on mount
    useEffect(() => {
        const fetchProducers = async () => {
            try {
                // Lista completa (paginação transparente): pedir size=1000 seria
                // clampado em 200 pelo backend e o autocompletar de produtores
                // ficaria incompleto.
                setProducers(await apiService.getAllProducers(token));
            } catch (error) {
                console.error("Erro ao carregar produtores", error);
                setNotification({ message: 'Erro ao carregar lista de produtores.', type: 'error' });
            }
        };
        fetchProducers();
    }, [apiService, token, setNotification]);

    // Transform flat DTO list into Matrix (Producers x Days).
    // Note: rows are independent after this point (keyed by array index, not by
    // resolved producerId) — if OCR split one producer into two misspelled rows,
    // correcting both via ProducerAutocomplete makes them share an id but they
    // stay as two separate rows. handleSaveBatch guards against saving duplicate
    // producer/date entries that this can produce.
    const processExtractedData = useCallback((dtoList) => {

        if (dtoList.length === 0) {
            setExtractedData([]);
            return;
        }

        // 1. Identify all unique dates to form columns
        // Sort dates chronologically
        const allDates = [...new Set(dtoList.map(item => item.data))].sort((a, b) => new Date(a) - new Date(b));
        setDaysInPeriod(allDates);

        // 2. Group by Producer
        const producerMap = new Map();

        // Helper map for fast lookup
        const registeredProducerMap = new Map(producers.map(p => [idTexto(p.id), p]));
        // Also map names to IDs for reverse lookup (normalization could be improved here)
        const nameToIdMap = new Map(producers.map(p => [p.nome.trim().toLowerCase(), idTexto(p.id)]));

        dtoList.forEach(item => {
            let processedItem = { ...item };

            // Backend ColetaExtraidaDto sends the OCR-detected name as `produtorNomeDetectado`
            // (older shape used `producerName`); accept either for compatibility.
            const detectedName = processedItem.produtorNomeDetectado ?? processedItem.producerName;

            // 1. Try to recover ID if missing, using the Name
            if (!processedItem.produtorId && detectedName) {
                const normalizedName = detectedName.trim().toLowerCase();
                if (nameToIdMap.has(normalizedName)) {
                    processedItem.produtorId = nameToIdMap.get(normalizedName);
                }
            }

            // 2. Try to resolve name from registered producers if ID is present (Canonical Name)
            let resolvedName = detectedName;
            if (processedItem.produtorId && registeredProducerMap.has(idTexto(processedItem.produtorId))) {
                resolvedName = registeredProducerMap.get(idTexto(processedItem.produtorId)).nome;
            }

            const prodKey = processedItem.produtorId || resolvedName || 'Desconhecido';

            if (!producerMap.has(prodKey)) {
                producerMap.set(prodKey, {
                    id: processedItem.produtorId,
                    name: resolvedName || 'Desconhecido',
                    collections: {} // Map date -> value
                });
            }
            producerMap.get(prodKey).collections[processedItem.data] = processedItem.quantidadeLitros;
        });

        setExtractedData(Array.from(producerMap.values()));
    }, [producers]);


    // Dropzone handler
    const onDrop = useCallback(async (acceptedFiles) => {
        if (acceptedFiles.length === 0) return;

        const file = acceptedFiles[0];
        setUploadStatus('UPLOADING');

        try {
            const formData = new FormData();
            formData.append('file', file);

            // Call API with selected OCR service and the sheet's reference period
            const result = await apiService.uploadColetaBatch(token, formData, ocrService, periodoAno, periodoMes);

            // Backend returns a list of ColetaExtraidaDto:
            // { produtorNomeDetectado, produtorId, matched, data: 'YYYY-MM-DD', quantidadeLitros }
            // Unmatched rows have produtorId=null / matched=false (surfaced for manual review).
            // Protect against null result.
            const dtoList = result || [];
            processExtractedData(dtoList);

            setUploadStatus('REVIEW');
            setNotification({ message: 'Arquivo processado com sucesso. Verifique os dados.', type: 'success' });
        } catch (error) {
            // Não esconde a falha no console, mesmo quando tratada.
            console.error(error);
            setUploadStatus('ERROR');

            // HTTP 429 = cota diária de OCR do usuário esgotada (B5): não é erro
            // de rede nem de autenticação, e não faz sentido retentar agora. O
            // upload não tem retry automático — aqui é só uma mensagem clara.
            if (error.status === 429) {
                const retryHint = formatRetryAfter(error.retryAfter);
                setNotification({
                    message: `Cota diária de OCR atingida.${retryHint ? ` ${retryHint}.` : ''}`,
                    type: 'error'
                });
                return;
            }

            setNotification({ message: 'Erro ao processar arquivo: ' + error.message, type: 'error' });
        }
    }, [token, apiService, setNotification, processExtractedData, ocrService, periodoAno, periodoMes]);

    const { getRootProps, getInputProps, isDragActive } = useDropzone({
        onDrop,
        accept: { 'image/*': [], 'application/pdf': [] },
        multiple: false
    });

    const handleValueChange = (prodIndex, date, newValue) => {
        const newData = [...extractedData];
        newData[prodIndex].collections[date] = newValue;
        setExtractedData(newData);
    };

    // Row-level producer resolution: fired when the user picks a suggestion
    // (or an inline-created producer resolves) in the ProducerAutocomplete cell.
    const handleProducerSelect = (prodIndex, producer) => {
        setExtractedData(prev => {
            const next = [...prev];
            next[prodIndex] = { ...next[prodIndex], id: producer.id, name: producer.nome };
            return next;
        });
    };

    // Registers a brand-new producer on the fly so a row with no match in the
    // system can still resolve to a valid produtorId before Salvar Lote.
    const handleProducerCreate = async (prodIndex, typedName) => {
        setPendingCreateCount(c => c + 1);
        try {
            const created = await apiService.createProducer(token, { nome: typedName });
            // Make it selectable by other unresolved rows in this same review session
            // without re-fetching the whole producer list.
            setProducers(prev => [...prev, created]);
            handleProducerSelect(prodIndex, created);
            return created;
        } finally {
            setPendingCreateCount(c => c - 1);
        }
    };

    // Helper function for retry with exponential backoff
    const retryWithBackoff = async (fn, maxRetries = 3, baseDelay = 1000) => {
        let lastError;
        for (let attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return await fn();
            } catch (error) {
                lastError = error;
                if (attempt < maxRetries) {
                    // Exponential backoff: 1s, 2s, 4s
                    const delay = baseDelay * Math.pow(2, attempt);
                    console.log(`Retry attempt ${attempt + 1}/${maxRetries} after ${delay}ms...`);
                    await new Promise(resolve => setTimeout(resolve, delay));
                }
            }
        }
        throw lastError;
    };

    const handleSaveBatch = async () => {
        setUploadStatus('SAVING');
        try {
            // Flatten matrix back to DTO list for saving
            const batchToSave = [];
            let skippedCount = 0;

            // Rows are corrected independently (no auto-merge), so two rows can end up
            // pointing at the same producer. Guard against silently double-submitting
            // the same producer/date pair rather than sending conflicting entries.
            const seenKeys = new Map(); // `${produtorId}_${date}` -> producer name
            let collision = null;

            extractedData.forEach(prod => {
                daysInPeriod.forEach(date => {
                    const val = prod.collections[date];
                    if (val !== undefined && val !== null && val !== '') {
                        // Fix potential comma decimals
                        const strVal = String(val).replace(',', '.');
                        const floatVal = parseFloat(strVal);

                        if (!isNaN(floatVal)) {
                            if (prod.id) {
                                const key = `${prod.id}_${date}`;
                                if (seenKeys.has(key) && !collision) {
                                    collision = { producerName: prod.name, date };
                                }
                                seenKeys.set(key, prod.name);

                                batchToSave.push({
                                    produtorId: idTexto(prod.id),
                                    data: date,
                                    quantidadeLitros: floatVal
                                });
                            } else {
                                skippedCount++;
                            }
                        } else {
                            console.warn(`Invalid number format for ${prod.name} on ${date}: ${val}`);
                        }
                    }
                });
            });

            if (collision) {
                const [year, month, day] = collision.date.split('-');
                setNotification({
                    message: `"${collision.producerName}" tem duas linhas com valores para ${day}/${month}/${year}. Apague o valor duplicado em uma das linhas antes de salvar.`,
                    type: 'error'
                });
                setUploadStatus('REVIEW');
                return;
            }

            console.log('Total items to save:', batchToSave.length); // Debugging

            if (batchToSave.length === 0) {
                if (skippedCount > 0) {
                    setNotification({ message: 'Nenhum dado válido para salvar. Vincule ou cadastre os produtores pendentes na tabela.', type: 'error' });
                } else {
                    setNotification({ message: 'Nenhum dado para salvar.', type: 'info' });
                }
                setUploadStatus('REVIEW'); // Stay in review
                return;
            }

            if (skippedCount > 0) {
                console.warn(`Skipping ${skippedCount} entries due to missing Producer ID.`);
            }

            // Chunking logic to avoid large transactions
            const CHUNK_SIZE = 50;
            const totalChunks = Math.ceil(batchToSave.length / CHUNK_SIZE);

            for (let i = 0; i < totalChunks; i++) {
                const start = i * CHUNK_SIZE;
                const end = start + CHUNK_SIZE;
                const chunk = batchToSave.slice(start, end);

                console.log(`Saving chunk ${i + 1}/${totalChunks} (${chunk.length} items)...`);

                // Use retry with backoff for each chunk
                await retryWithBackoff(() => apiService.salvarColetasEmLote(token, chunk));
            }

            setUploadStatus('SUCCESS');
            setNotification({ message: 'Lote salvo com sucesso!', type: 'success' });

        } catch (error) {
            console.error('Failed after all retries:', error);
            setUploadStatus('REVIEW'); // Go back to review instead of showing error state
            setNotification({ message: 'Erro ao salvar (parte dos dados pode ter sido salva). Tente novamente.', type: 'error' });
        }
    };

    const handleCancel = () => {
        setUploadStatus('IDLE');
        setExtractedData([]);
        setDaysInPeriod([]);
    };

    // --- Renders ---

    if (uploadStatus === 'SUCCESS') {
        return (
            <div className="max-w-4xl mx-auto text-center p-12 bg-white rounded-xl shadow-md mt-8">
                <div className="mx-auto flex items-center justify-center h-24 w-24 rounded-full bg-green-100 mb-6">
                    <CheckIcon className="h-12 w-12 text-green-600" />
                </div>
                <h2 className="text-3xl font-bold text-gray-800 mb-4">Importação Concluída!</h2>
                <p className="text-gray-600 mb-8">Todas as coletas foram salvas com sucesso no sistema.</p>
                <button onClick={handleCancel} className="btn-primary">Importar Novo Arquivo</button>
            </div>
        );
    }

    return (
        <div className="max-w-6xl mx-auto anim-pagina">
            <h1 className="text-3xl font-bold text-gray-800 mb-8">Importar Coletas</h1>

            {/* Upload Area - Show only if not reviewing */}
            {uploadStatus === 'IDLE' || uploadStatus === 'UPLOADING' || uploadStatus === 'ERROR' ? (
                <div className="bg-white p-8 rounded-xl shadow-md">
                    {/* OCR Service Toggle */}
                    <div className="flex items-center justify-center gap-4 mb-6 p-4 bg-gray-50 rounded-lg">
                        <span className="text-sm font-medium text-gray-600">Serviço OCR:</span>
                        <div className="flex items-center bg-gray-200 rounded-full p-1">
                            <button
                                onClick={() => setOcrService('gemini')}
                                className={`px-4 py-2 rounded-full text-sm font-medium transition-colors duration-200 ${ocrService === 'gemini'
                                        ? 'bg-blue-600 text-white shadow-sm'
                                        : 'text-gray-600 hover:text-gray-800'
                                    }`}
                            >
                                Gemini
                            </button>
                            <button
                                onClick={() => setOcrService('azure')}
                                className={`px-4 py-2 rounded-full text-sm font-medium transition-colors duration-200 ${ocrService === 'azure'
                                        ? 'bg-blue-600 text-white shadow-sm'
                                        : 'text-gray-600 hover:text-gray-800'
                                    }`}
                            >
                                Azure
                            </button>
                        </div>
                    </div>

                    {/* Período de referência da planilha */}
                    <div className="flex flex-col md:flex-row md:items-center justify-center gap-4 mb-6 p-4 bg-gray-50 rounded-lg">
                        <span className="text-sm font-medium text-gray-600">Mês de referência das coletas:</span>
                        <select
                            value={periodoMes}
                            onChange={(e) => setPeriodoMes(e.target.value)}
                            aria-label="Mês de referência das coletas"
                            className="input-field w-40"
                        >
                            {mesesOptions.map(m => (
                                <option key={m.value} value={m.value}>{m.label}</option>
                            ))}
                        </select>
                        <select
                            value={periodoAno}
                            onChange={(e) => setPeriodoAno(e.target.value)}
                            aria-label="Ano de referência das coletas"
                            className="input-field w-28"
                        >
                            {anosOptions.map(a => (
                                <option key={a} value={a}>{a}</option>
                            ))}
                        </select>
                        <span className="text-xs text-gray-500">
                            A planilha traz os <strong>dias</strong> das coletas; o ano e o mês são definidos aqui.
                        </span>
                    </div>

                    <div {...getRootProps()} className={`border-2 border-dashed rounded-xl p-12 text-center cursor-pointer transition-colors ${isDragActive ? 'border-blue-500 bg-blue-50' : 'border-gray-300 hover:border-blue-400'}`}>
                        <input {...getInputProps()} />
                        <div className="flex flex-col items-center">
                            <UploadIcon />
                            <p className="mt-4 text-xl font-medium text-gray-700">Arrastar e soltar arquivo aqui</p>
                            <p className="mt-2 text-gray-500">ou clique para selecionar (PDF ou Imagem)</p>
                            <p className="mt-8 text-sm text-gray-400">Tamanho máximo: 10MB</p>
                        </div>
                    </div>
                    {uploadStatus === 'UPLOADING' && <p className="text-center mt-4 text-blue-600 font-semibold animate-pulse">Processando arquivo...</p>}
                </div>
            ) : null}

            {/* Review Grid - Show if Reviewing or Saving */}
            {(uploadStatus === 'REVIEW' || uploadStatus === 'SAVING') && (
                <div className="bg-white p-6 rounded-xl shadow-md overflow-hidden">
                    <div className="flex justify-between items-center mb-6">
                        <h2 className="text-xl font-semibold text-gray-700">Revisar Dados Extraídos</h2>
                        <div className="flex gap-4">
                            <button onClick={handleCancel} className="px-4 py-2 border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50">Cancelar</button>
                            <button
                                onClick={handleSaveBatch}
                                disabled={uploadStatus === 'SAVING' || pendingCreateCount > 0}
                                className="btn-primary flex items-center gap-2"
                            >
                                {uploadStatus === 'SAVING' ? 'Salvando...' : pendingCreateCount > 0 ? 'Aguarde...' : 'Salvar Lote'}
                            </button>
                        </div>
                    </div>

                    <div className="overflow-x-auto">
                        <table className="min-w-full divide-y divide-gray-200 border">
                            <thead className="bg-gray-50">
                                <tr>
                                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider sticky left-0 bg-gray-50 z-10 border-r min-w-[240px]">Produtor</th>
                                    {daysInPeriod.map(date => {
                                        // Format date MM/DD from YYYY-MM-DD string avoiding timezone issues
                                        const [, month, day] = date.split('-');
                                        const formatted = `${parseInt(day)}/${parseInt(month)}`;
                                        return <th key={date} className="px-2 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider min-w-[60px]">{formatted}</th>
                                    })}
                                </tr>
                            </thead>
                            <tbody className="bg-white divide-y divide-gray-200">
                                {extractedData.map((prod, pIdx) => (
                                    <tr key={pIdx} className="hover:bg-gray-50">
                                        <td className="px-4 py-2 text-sm font-medium text-gray-900 sticky left-0 bg-white z-10 border-r min-w-[240px]">
                                            <ProducerAutocomplete
                                                value={{ id: prod.id, nome: prod.name }}
                                                producers={producers}
                                                disabled={uploadStatus === 'SAVING'}
                                                onSelectExisting={(producer) => handleProducerSelect(pIdx, producer)}
                                                onCreateNew={(typedName) => handleProducerCreate(pIdx, typedName)}
                                            />
                                        </td>
                                        {daysInPeriod.map(date => (
                                            <td key={date} className="px-1 py-1 text-center whitespace-nowrap border-l border-gray-100">
                                                <input
                                                    type="number"
                                                    value={prod.collections[date] !== undefined ? prod.collections[date] : ''}
                                                    onChange={(e) => handleValueChange(pIdx, date, e.target.value)}
                                                    className="w-full text-center p-1 rounded focus:ring-1 focus:ring-blue-500 focus:outline-none hover:bg-gray-100 transition-colors"
                                                    style={{ maxWidth: '80px' }}
                                                />
                                            </td>
                                        ))}
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
};

export default ImportacaoColetasPage;
