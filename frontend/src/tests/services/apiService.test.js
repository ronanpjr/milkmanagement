import { apiService, paginarTudoComAviso, formatRetryAfter } from '../../services/apiService';

// Mock global fetch
global.fetch = jest.fn();

describe('apiService', () => {
    const originalApiBaseUrl = process.env.REACT_APP_API_BASE_URL;

    beforeEach(() => {
        fetch.mockClear();
        process.env.REACT_APP_API_BASE_URL = 'http://localhost:8080';
        delete window.__ERP_API_BASE_URL__;
        document.head.querySelectorAll('meta[name="erp-api-base-url"]').forEach((el) => el.remove());
    });

    afterAll(() => {
        process.env.REACT_APP_API_BASE_URL = originalApiBaseUrl;
    });

    const adicionarMetaApiBaseUrl = (valor) => {
        const meta = document.createElement('meta');
        meta.name = 'erp-api-base-url';
        meta.content = valor;
        document.head.appendChild(meta);
    };

    describe('base URL', () => {
        it('usa a meta build-time no app empacotado, mesmo com process.env local', async () => {
            adicionarMetaApiBaseUrl('https://api.example.com');
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => [],
            });

            await apiService.getProducers('fake-token');

            expect(fetch).toHaveBeenCalledWith(
                'https://api.example.com/api/suppliers?page=0&size=20&sortBy=nome&sortDir=asc',
                expect.anything()
            );
        });

        it('o override do preload vence a meta e o process.env', async () => {
            window.__ERP_API_BASE_URL__ = 'https://override.example';
            adicionarMetaApiBaseUrl('https://meta.example');
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => [],
            });

            await apiService.getProducers('fake-token');

            expect(fetch).toHaveBeenCalledWith(
                'https://override.example/api/suppliers?page=0&size=20&sortBy=nome&sortDir=asc',
                expect.anything()
            );
        });
    });

    describe('getProducers', () => {
        it('should fetch producers successfully', async () => {
            const mockProducers = [{ id: 1, nome: 'Produtor 1' }];
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => mockProducers,
            });

            const result = await apiService.getProducers('fake-token');
            expect(result).toEqual(mockProducers);
            expect(fetch).toHaveBeenCalledWith(
                'http://localhost:8080/api/suppliers?page=0&size=20&sortBy=nome&sortDir=asc',
                expect.objectContaining({
                    headers: { 'Authorization': 'Bearer fake-token' },
                })
            );
        });

        it('should handle API errors', async () => {
            fetch.mockResolvedValueOnce({
                ok: false,
                text: async () => JSON.stringify({ message: 'Erro ao buscar produtores' }),
            });

            await expect(apiService.getProducers('fake-token')).rejects.toThrow('Erro ao buscar produtores');
        });
    });

    describe('createFechamento', () => {
        it('should create fechamento successfully', async () => {
            const mockResponse = { id: 100, ...{ produtorId: 1, mes: 5 } };
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => mockResponse,
            });

            const payload = { produtorId: 1, mes: 5 };
            const result = await apiService.createFechamento('fake-token', payload);

            expect(result).toEqual(mockResponse);
            expect(fetch).toHaveBeenCalledWith(
                'http://localhost:8080/api/settlements',
                expect.objectContaining({
                    method: 'POST',
                    body: JSON.stringify(payload),
                })
            );
        });
    });

    describe('paginação transparente (getAll*)', () => {
        // O backend limita o tamanho de página em 200 (PaginationUtil) e devolve
        // os totais da consulta inteira. Os métodos `getAll*` percorrem as páginas
        // para devolver a lista completa — é isso que impede a UI de exibir
        // dados truncados quando alguém passa de 200 registros.

        const pageResponse = (content, last) => ({
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({ content, last }),
        });

        it('getAllProducers deve agregar todas as páginas até last=true', async () => {
            const page1 = Array.from({ length: 200 }, (_, i) => ({ id: i + 1, nome: `P${i + 1}` }));
            const page2 = [{ id: 201, nome: 'P201' }];

            fetch.mockResolvedValueOnce(pageResponse(page1, false));
            fetch.mockResolvedValueOnce(pageResponse(page2, true));

            const result = await apiService.getAllProducers('fake-token');

            expect(result).toHaveLength(201);
            expect(result[200]).toEqual({ id: 201, nome: 'P201' });
            expect(fetch).toHaveBeenCalledTimes(2);
            expect(fetch.mock.calls[0][0]).toContain('size=200');
            expect(fetch.mock.calls[0][0]).toContain('page=0');
            expect(fetch.mock.calls[1][0]).toContain('page=1');
        });

        it('D6: getAllColetasComAviso avisa quando a lista bate no teto de paginação', async () => {
            // Regressão: o laço de paginação tem teto de segurança
            // (MAX_PAGINATION_PAGES) contra backend inconsistente. Ao atingir o
            // teto, a lista vinha truncada e o código devolvia os 10.000
            // registros SEM AVISAR — o dashboard e os relatórios mostravam
            // totais subestimados em silêncio. Aqui simulamos um servidor que
            // nunca devolve `last: true`.
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            // 50 páginas (o teto) sem last=true.
            for (let p = 0; p < 50; p += 1) {
                fetch.mockResolvedValueOnce(pageResponse(paginaCheia, false));
            }

            const avisos = [];
            const resultado = await apiService.getAllColetasComAviso(
                'fake-token', {}, (total) => avisos.push(total)
            );

            expect(resultado).toHaveLength(200 * 50);
            expect(avisos).toEqual([200 * 50]);
        });

        it('D6: getAllColetasComAviso nao avisa quando a paginacao termina normalmente', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], true));

            const avisos = [];
            await apiService.getAllColetasComAviso('fake-token', {}, (t) => avisos.push(t));

            expect(avisos).toEqual([]);
        });

        it('D6: getAllColetasComAviso deve respeitar filtros e agregar paginas', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], true));

            const result = await apiService.getAllColetasComAviso('fake-token', {
                produtorId: 7, inicio: '2026-01-01', fim: '2026-01-31',
            });

            expect(result).toEqual([{ id: 1 }]);
            expect(fetch.mock.calls[0][0]).toContain('produtorId=7');
        });

        it('achado20: paginarTudoComAviso devolve o MESMO array e avisa quando bate no teto', async () => {
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            const fetchPage = jest.fn(() => Promise.resolve({ content: paginaCheia, last: false }));

            const avisos = [];
            const resultado = await paginarTudoComAviso(fetchPage, (total) => avisos.push(total));

            expect(resultado).toHaveLength(200 * 50);
            expect(fetchPage).toHaveBeenCalledTimes(50);
            expect(avisos).toEqual([200 * 50]);
        });

        it('achado20: paginarTudoComAviso nao avisa quando a paginacao termina normalmente', async () => {
            const fetchPage = jest.fn(() => Promise.resolve({ content: [{ id: 1 }], last: true }));

            const avisos = [];
            const resultado = await paginarTudoComAviso(fetchPage, (total) => avisos.push(total));

            expect(resultado).toEqual([{ id: 1 }]);
            expect(avisos).toEqual([]);
        });

        it('achado20: getAllProducersComAviso avisa quando a lista bate no teto', async () => {
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            for (let p = 0; p < 50; p += 1) {
                fetch.mockResolvedValueOnce(pageResponse(paginaCheia, false));
            }

            const avisos = [];
            const resultado = await apiService.getAllProducersComAviso(
                'fake-token', (total) => avisos.push(total)
            );

            expect(resultado).toHaveLength(200 * 50);
            expect(avisos).toEqual([200 * 50]);
        });

        it('achado20: getAllProducersComAviso nao avisa quando termina normalmente', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], true));

            const avisos = [];
            const resultado = await apiService.getAllProducersComAviso(
                'fake-token', (total) => avisos.push(total)
            );

            expect(resultado).toEqual([{ id: 1 }]);
            expect(avisos).toEqual([]);
        });

        it('achado20: getAllFechamentosComAviso devolve o array e avisa no teto', async () => {
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            for (let p = 0; p < 50; p += 1) {
                fetch.mockResolvedValueOnce(pageResponse(paginaCheia, false));
            }

            const avisos = [];
            const resultado = await apiService.getAllFechamentosComAviso(
                'fake-token', {}, (total) => avisos.push(total)
            );

            expect(Array.isArray(resultado)).toBe(true);
            expect(avisos).toEqual([200 * 50]);
        });

        it('quando o servidor informa totalPages, busca TODAS as páginas (sem truncar em 50)', async () => {
            // Regressão: o teto de 50 páginas (10.000 registros) truncava a
            // lista em silêncio, e o relatório de 6 meses (~36 mil coletas =
            // 183 páginas) mostrava totais subestimados. Com `totalPages`, o
            // cliente sabe quantas páginas existem e busca todas.
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            const TOTAL_PAGINAS = 60; // acima do teto antigo de 50

            const responder = (page) => {
                const conteudo = page === TOTAL_PAGINAS - 1
                    ? [{ id: 99999 }]
                    : paginaCheia;
                return {
                    ok: true,
                    headers: { get: () => 'application/json' },
                    json: async () => ({ content: conteudo, last: page === TOTAL_PAGINAS - 1, totalPages: TOTAL_PAGINAS }),
                };
            };
            fetch.mockImplementation(async (url) => responder(Number(/page=(\d+)/.exec(url)[1])));

            const resultado = await apiService.getAllColetas('fake-token', {});

            expect(resultado).toHaveLength(200 * (TOTAL_PAGINAS - 1) + 1);
            expect(resultado[resultado.length - 1]).toEqual({ id: 99999 });
        });

        it('quando o servidor informa totalPages, avisa se o volume estourar o teto de 100.000 registros', async () => {
            const paginaCheia = Array.from({ length: 200 }, (_, i) => ({ id: i + 1 }));
            const TOTAL_PAGINAS = 600; // acima do teto de volume (500 páginas)

            const responder = (page) => ({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ content: paginaCheia, last: false, totalPages: TOTAL_PAGINAS }),
            });
            fetch.mockImplementation(async (url) => responder(Number(/page=(\d+)/.exec(url)[1])));

            const avisos = [];
            const resultado = await apiService.getAllColetasComAviso(
                'fake-token', {}, (total) => avisos.push(total)
            );

            expect(resultado).toHaveLength(200 * 500);
            expect(avisos).toEqual([200 * 500]);
        });

        it('D6: getAll* continua devolvendo so o array, sem metadado', async () => {
            // Os chamadores existentes esperam um array direto.
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], true));

            const result = await apiService.getAllProducers('fake-token');

            expect(Array.isArray(result)).toBe(true);
        });

        it('getAllColetas deve respeitar os filtros e agregar todas as páginas', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1, data: '2026-01-01' }], false));
            fetch.mockResolvedValueOnce(pageResponse([{ id: 2, data: '2026-01-02' }], true));

            const result = await apiService.getAllColetas('fake-token', {
                produtorId: 7, inicio: '2026-01-01', fim: '2026-01-31',
            });

            expect(result).toHaveLength(2);
            expect(fetch.mock.calls[0][0]).toContain('produtorId=7');
            expect(fetch.mock.calls[0][0]).toContain('inicio=2026-01-01');
            expect(fetch.mock.calls[0][0]).toContain('fim=2026-01-31');
        });

        it('getAllFechamentos deve agregar todas as páginas', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], false));
            fetch.mockResolvedValueOnce(pageResponse([{ id: 2 }], true));

            const result = await apiService.getAllFechamentos('fake-token');

            expect(result).toHaveLength(2);
            expect(fetch.mock.calls[0][0]).toContain('/api/settlements?');
        });

        it('deve parar em uma requisição quando a primeira página já é a última', async () => {
            fetch.mockResolvedValueOnce(pageResponse([{ id: 1 }], true));

            const result = await apiService.getAllFechamentos('fake-token');

            expect(result).toHaveLength(1);
            expect(fetch).toHaveBeenCalledTimes(1);
        });

        it('deve tolerar resposta sem content (null) sem laço infinito', async () => {
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({}),
            });

            const result = await apiService.getAllColetas('fake-token', {});

            expect(result).toEqual([]);
            expect(fetch).toHaveBeenCalledTimes(1);
        });
    });

    describe('uploadColetaBatch', () => {
        const formData = new FormData();

        it('deve enviar ano/mes quando informados (importação de planilha antiga)', async () => {
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => [],
            });

            await apiService.uploadColetaBatch('fake-token', formData, 'gemini', '2024', '05');

            expect(fetch.mock.calls[0][0]).toBe(
                'http://localhost:8080/api/collections/upload-batch?ocrService=gemini&ano=2024&mes=05'
            );
        });

        it('deve omitir ano/mes quando não informados (backend assume mês passado)', async () => {
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => [],
            });

            await apiService.uploadColetaBatch('fake-token', formData, 'gemini');

            expect(fetch.mock.calls[0][0]).toBe(
                'http://localhost:8080/api/collections/upload-batch?ocrService=gemini'
            );
        });

        it('propaga status=429 e retryAfter no erro de cota diária de OCR', async () => {
            // Contrato B5: o upload pode responder HTTP 429 quando a cota diária
            // de OCR do usuário acaba. handleResponse precisa anexar status e o
            // header Retry-After (em segundos) para a UI montar a mensagem.
            fetch.mockResolvedValueOnce({
                ok: false,
                status: 429,
                headers: { get: (name) => (name === 'Retry-After' ? '7200' : null) },
                text: async () => JSON.stringify({ message: 'Cota diária de OCR atingida.' }),
            });

            const error = await apiService.uploadColetaBatch('fake-token', formData).catch((e) => e);

            expect(error).toBeInstanceOf(Error);
            expect(error.message).toBe('Cota diária de OCR atingida.');
            expect(error.apiMessage).toBe('Cota diária de OCR atingida.');
            expect(error.status).toBe(429);
            expect(error.retryAfter).toBe('7200');
        });

        it('anexa retryAfter=null quando o header Retry-After está ausente', async () => {
            fetch.mockResolvedValueOnce({
                ok: false,
                status: 429,
                headers: { get: () => null },
                text: async () => JSON.stringify({ message: 'Cota atingida.' }),
            });

            const error = await apiService.uploadColetaBatch('fake-token', formData).catch((e) => e);

            expect(error.status).toBe(429);
            expect(error.retryAfter).toBeNull();
        });
    });

    describe('formatRetryAfter', () => {
        it('arredonda segundos para minutos', () => {
            expect(formatRetryAfter(120)).toBe('Tente novamente em 2 minutos');
            expect(formatRetryAfter(60)).toBe('Tente novamente em 1 minuto');
        });

        it('arredonda para horas a partir de 60 minutos', () => {
            expect(formatRetryAfter(7200)).toBe('Tente novamente em 2 horas');
            expect(formatRetryAfter(3600)).toBe('Tente novamente em 1 hora');
        });

        it('usa "alguns segundos" quando o tempo é curto', () => {
            expect(formatRetryAfter(30)).toBe('Tente novamente em alguns segundos');
        });

        it('devolve string vazia quando o valor é ausente ou inválido', () => {
            expect(formatRetryAfter(null)).toBe('');
            expect(formatRetryAfter(undefined)).toBe('');
            expect(formatRetryAfter(0)).toBe('');
            expect(formatRetryAfter('abc')).toBe('');
        });
    });

    describe('handleResponse Logic (implicit via methods)', () => {
        it('should return null for empty success responses (e.g. 204)', async () => {
            // Simulate a DELETE request returning 204 No Content
            fetch.mockResolvedValueOnce({
                ok: true,
                headers: { get: () => null }, // No content-type
                json: async () => ({}),
            });

            const result = await apiService.deleteProducer('fake-token', 123);
            expect(result).toBeNull();
        });

        it('should throw error with status text if body is empty on error', async () => {
            fetch.mockResolvedValueOnce({
                ok: false,
                status: 500,
                text: async () => 'Internal Server Error HTML', // Logic tries to parse JSON, fails, then throws this text
            });

            await expect(apiService.getProducers('fake-token')).rejects.toThrow('Internal Server Error HTML');
        });
    });
});
