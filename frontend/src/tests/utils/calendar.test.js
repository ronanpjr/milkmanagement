import {
    MESES_ABREVIADOS,
    abreviacaoMes,
    anoMesDeDataISO,
    chaveDataISO,
    chaveMes,
    chaveMesDeData,
    chaveMesDeDataISO,
    chaveSemanaDeDataISO,
    dataBRDeDataISO,
    ultimosMeses,
} from '../../utils/calendar';

describe('calendar', () => {
    describe('MESES_ABREVIADOS', () => {
        test('tem 12 meses, com a inicial minúscula e o ponto final', () => {
            expect(MESES_ABREVIADOS).toHaveLength(12);
            expect(MESES_ABREVIADOS[0]).toBe('jan.');
            expect(MESES_ABREVIADOS[8]).toBe('set.');
            expect(MESES_ABREVIADOS[11]).toBe('dez.');
        });
    });

    describe('abreviacaoMes', () => {
        test('devolve a inicial maiúscula, como o gráfico exibia', () => {
            expect(abreviacaoMes(1)).toBe('Jan.');
            expect(abreviacaoMes(5)).toBe('Mai.');
            expect(abreviacaoMes(12)).toBe('Dez.');
        });

        test('devolve string vazia para mês inválido', () => {
            expect(abreviacaoMes(0)).toBe('');
            expect(abreviacaoMes(13)).toBe('');
            expect(abreviacaoMes(undefined)).toBe('');
        });
    });

    describe('chaveMes', () => {
        test('monta a chave mes/ano com dois dígitos', () => {
            expect(chaveMes(2026, 5)).toBe('Mai./26');
            expect(chaveMes(2026, 1)).toBe('Jan./26');
            expect(chaveMes(2025, 12)).toBe('Dez./25');
        });

        test('devolve string vazia para mês ou ano inválidos', () => {
            expect(chaveMes(2026, 13)).toBe('');
            expect(chaveMes(NaN, 5)).toBe('');
        });
    });

    describe('anoMesDeDataISO', () => {
        test('extrai ano e mês sem passar por fuso', () => {
            expect(anoMesDeDataISO('2026-10-20')).toEqual({ ano: 2026, mes: 10 });
            expect(anoMesDeDataISO('2026-01-01')).toEqual({ ano: 2026, mes: 1 });
        });

        test('ignora a parte de hora, se houver', () => {
            expect(anoMesDeDataISO('2026-10-20T00:00:00')).toEqual({ ano: 2026, mes: 10 });
        });

        test('devolve null para data não reconhecível', () => {
            expect(anoMesDeDataISO('')).toBeNull();
            expect(anoMesDeDataISO(null)).toBeNull();
            expect(anoMesDeDataISO('20/10/2026')).toBeNull();
            expect(anoMesDeDataISO('2026-13-01')).toBeNull();
        });
    });

    describe('chaveMesDeData', () => {
        test('monta a chave a partir da data ISO', () => {
            expect(chaveMesDeData('2026-10-20')).toBe('Out./26');
            expect(chaveMesDeData('2025-12-31')).toBe('Dez./25');
        });

        test('devolve string vazia para data inválida', () => {
            expect(chaveMesDeData('lixo')).toBe('');
            expect(chaveMesDeData(undefined)).toBe('');
        });
    });

    describe('dataBRDeDataISO', () => {
        test('formata dd/mm/aaaa por fatiamento da string', () => {
            expect(dataBRDeDataISO('2026-10-20')).toBe('20/10/2026');
            expect(dataBRDeDataISO('2026-01-05')).toBe('05/01/2026');
        });

        test('ignora a parte de hora, se houver', () => {
            expect(dataBRDeDataISO('2026-10-20T00:00:00')).toBe('20/10/2026');
        });

        test('devolve string vazia para data inválida', () => {
            expect(dataBRDeDataISO('')).toBe('');
            expect(dataBRDeDataISO('20/10/2026')).toBe('');
        });
    });

    describe('ultimosMeses', () => {
        test('devolve a quantidade pedida, do mais antigo ao mais recente', () => {
            const meses = ultimosMeses(6, new Date(2026, 9, 20)); // outubro de 2026
            expect(meses).toHaveLength(6);
            expect(meses[0]).toEqual({ ano: 2026, mes: 5 });
            expect(meses[5]).toEqual({ ano: 2026, mes: 10 });
        });

        test('atravessa a virada de ano com o ano correto de cada mês', () => {
            // Janeiro de 2026: os 6 meses vão de agosto/2025 a janeiro/2026.
            const meses = ultimosMeses(6, new Date(2026, 0, 15));
            expect(meses[0]).toEqual({ ano: 2025, mes: 8 });
            expect(meses[4]).toEqual({ ano: 2025, mes: 12 });
            expect(meses[5]).toEqual({ ano: 2026, mes: 1 });
        });
    });
});

describe('chaveDataISO', () => {
    test('normaliza para a chave civil YYYY-MM-DD', () => {
        expect(chaveDataISO('2026-10-20')).toBe('2026-10-20');
        expect(chaveDataISO('2026-10-20T00:00:00')).toBe('2026-10-20');
    });

    test('devolve null para data fora do formato ISO', () => {
        expect(chaveDataISO('20/10/2026')).toBeNull();
        expect(chaveDataISO('')).toBeNull();
        expect(chaveDataISO(undefined)).toBeNull();
    });
});


describe('chaveMesDeDataISO', () => {
    test('devolve a chave YYYY-MM do mês da data', () => {
        expect(chaveMesDeDataISO('2026-10-20')).toBe('2026-10');
        expect(chaveMesDeDataISO('2026-01-05')).toBe('2026-01');
    });

    test('devolve string vazia para data fora do formato ISO', () => {
        expect(chaveMesDeDataISO('20/10/2026')).toBe('');
        expect(chaveMesDeDataISO(undefined)).toBe('');
    });
});

describe('chaveSemanaDeDataISO', () => {
    test('a semana começa na segunda-feira', () => {
        // 2026-10-19 é uma segunda-feira.
        expect(chaveSemanaDeDataISO('2026-10-19')).toBe('2026-10-19');
        // ...e domingo fecha a mesma semana.
        expect(chaveSemanaDeDataISO('2026-10-25')).toBe('2026-10-19');
    });

    test('semanas diferentes geram chaves diferentes e ordenáveis', () => {
        const segunda = chaveSemanaDeDataISO('2026-10-19');
        const outra = chaveSemanaDeDataISO('2026-10-26');
        expect(outra > segunda).toBe(true);
    });

    test('atravessa a virada de mês e de ano', () => {
        // 2025-12-31 é uma quarta-feira: a semana começou em 29/12.
        expect(chaveSemanaDeDataISO('2025-12-31')).toBe('2025-12-29');
        // 2026-01-01 é uma quinta-feira: a mesma semana de 29/12.
        expect(chaveSemanaDeDataISO('2026-01-01')).toBe('2025-12-29');
    });

    test('devolve string vazia para data fora do formato ISO', () => {
        expect(chaveSemanaDeDataISO('20/10/2026')).toBe('');
        expect(chaveSemanaDeDataISO(undefined)).toBe('');
    });
});
