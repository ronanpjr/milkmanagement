// src/utils/calendar.js
//
// Chaves de calendário calculadas SEM `Intl`.
//
// Motivo (issue #34): `toLocaleString`/`toLocaleDateString` custam ~200 µs por
// chamada. As telas de Dashboard e Relatórios chamavam isso **uma vez por
// linha** para agregar ~7.000 coletas, o que travava a main thread por 1,1 a
// 1,6 s — e, como o browser não pinta nem roda `requestAnimationFrame` nesse
// intervalo, toda animação em curso congelava.
//
// A alternativa não é "formatar mais rápido": é **não formatar**. Nomes de mês
// em pt-BR são um dado fixo (uma tabela), não algo a ser derivado de locale a
// cada linha. E a data ISO 'YYYY-MM-DD' já é a chave UTC canônica: para
// exibir, basta fatiar a string.
//
// Tudo aqui é determinístico e independente de fuso: a mesma entrada produz a
// mesma saída em qualquer máquina.

// Abreviações de mês em pt-BR — exatamente o que
// `new Date(...).toLocaleString('pt-BR', { month: 'short' })` produzia
// (incluindo o ponto final). Mantidas iguais para que o rótulo do gráfico não
// mude junto com a performance.
export const MESES_ABREVIADOS = [
    'jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.',
    'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.',
];

/**
 * Abreviação do mês, com a inicial maiúscula, como o gráfico exibia.
 * @param {number} mes1a12 mês de 1 a 12
 * @returns {string} 'Jan.' … 'Dez.' (string vazia se o mês for inválido)
 */
export const abreviacaoMes = (mes1a12) => {
    const abrev = MESES_ABREVIADOS[mes1a12 - 1];
    if (!abrev) return '';
    return abrev.charAt(0).toUpperCase() + abrev.slice(1);
};

/**
 * Chave de mês/ano para os gráficos: 'Mai./26'.
 * @param {number} ano ano completo (2026)
 * @param {number} mes1a12 mês de 1 a 12
 * @returns {string}
 */
export const chaveMes = (ano, mes1a12) => {
    const abrev = abreviacaoMes(mes1a12);
    if (!abrev || !Number.isInteger(ano)) return '';
    return `${abrev}/${String(ano).slice(-2)}`;
};

// Regex de data civil ISO. Aceita 'YYYY-MM-DD' e 'YYYY-MM-DDTHH:mm:ss...'
// (o backend serializa `LocalDate` como data pura, mas a defesa evita que um
// valor inesperado vire "undefined/undefined/..." na tela).
const REGEX_DATA_ISO = /^(\d{4})-(\d{2})-(\d{2})/;

/**
 * Fatura uma data ISO em ano/mês/dia, sem `Date` e sem fuso.
 * @param {string} dataISO
 * @returns {{ano: string, mes: string, dia: string}|null}
 */
const partesDataISO = (dataISO) => {
    const match = REGEX_DATA_ISO.exec(String(dataISO || ''));
    if (!match) return null;
    return { ano: match[1], mes: match[2], dia: match[3] };
};

/**
 * Extrai ano e mês de uma data ISO 'YYYY-MM-DD' (ou com hora) sem passar por
 * `Date` — e, principalmente, sem passar por fuso: a string já é a data civil.
 * @param {string} dataISO
 * @returns {{ano: number, mes: number}|null} null se a data não for ISO
 */
export const anoMesDeDataISO = (dataISO) => {
    const partes = partesDataISO(dataISO);
    if (!partes) return null;
    const ano = parseInt(partes.ano, 10);
    const mes = parseInt(partes.mes, 10);
    if (!Number.isInteger(ano) || !Number.isInteger(mes) || mes < 1 || mes > 12) {
        return null;
    }
    return { ano, mes };
};

/**
 * Chave de mês/ano de uma data ISO: 'Mai./26'.
 * @param {string} dataISO 'YYYY-MM-DD'
 * @returns {string} string vazia se a data não for ISO reconhecível
 */
export const chaveMesDeData = (dataISO) => {
    const anoMes = anoMesDeDataISO(dataISO);
    if (!anoMes) return '';
    return chaveMes(anoMes.ano, anoMes.mes);
};

/**
 * Chave de mês/ano a partir de uma data ISO: 'YYYY-MM'.
 *
 * Serve de balde de agregação quando o rótulo não importa (a ordenação
 * cronológica vem da comparação lexicográfica das strings, sem `Date`).
 * O rótulo bonito vem de {@link chaveMesDeData}.
 *
 * @param {string} dataISO 'YYYY-MM-DD'
 * @returns {string} string vazia se a data não for ISO reconhecível
 */
export const chaveMesDeDataISO = (dataISO) => {
    const anoMes = anoMesDeDataISO(dataISO);
    if (!anoMes) return '';
    return `${anoMes.ano}-${String(anoMes.mes).padStart(2, '0')}`;
};

/**
 * Chave da semana de uma data ISO: a segunda-feira da semana, em 'YYYY-MM-DD'.
 *
 * A semana começa na segunda (convenção ISO 8601) e a chave é sempre uma data
 * real — assim a comparação lexicográfica das chaves já é a ordem cronológica,
 * sem `Date` no `sort`.
 *
 * Cálculo sem fuso: converte a data civil para "dias desde a época" (UTC) e
 * usa o fato de 1970-01-01 ter sido uma quinta-feira.
 *
 * @param {string} dataISO 'YYYY-MM-DD'
 * @returns {string} string vazia se a data não for ISO reconhecível
 */
export const chaveSemanaDeDataISO = (dataISO) => {
    const partes = partesDataISO(dataISO);
    if (!partes) return '';
    const ano = parseInt(partes.ano, 10);
    const mes = parseInt(partes.mes, 10);
    const dia = parseInt(partes.dia, 10);
    if (!Number.isInteger(ano) || !Number.isInteger(mes) || !Number.isInteger(dia)) return '';

    const MS_POR_DIA = 86400000;
    const diasDesdeEpoca = Math.floor(Date.UTC(ano, mes - 1, dia) / MS_POR_DIA);
    // 1970-01-01 foi uma quinta-feira (4), na convenção "domingo = 0".
    const diaDaSemana = (((diasDesdeEpoca + 4) % 7) + 7) % 7;
    // Dias até a segunda-feira da mesma semana.
    const diasAteSegunda = (diaDaSemana + 6) % 7;
    const inicioDaSemana = diasDesdeEpoca - diasAteSegunda;

    const d = new Date(inicioDaSemana * MS_POR_DIA);
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
};

/**
 * Normaliza uma data para a chave civil 'YYYY-MM-DD', ou null se não for uma
 * data ISO. Serve de chave de balde: como a chave é ISO, a comparação
 * lexicográfica das chaves já é a ordem cronológica (sem `Date` no `sort`).
 * @param {string} dataISO
 * @returns {string|null}
 */
export const chaveDataISO = (dataISO) => {
    const partes = partesDataISO(dataISO);
    if (!partes) return null;
    return `${partes.ano}-${partes.mes}-${partes.dia}`;
};

/**
 * Data ISO no formato brasileiro, por fatiamento da string.
 *
 * `new Date('2026-10-20').toLocaleDateString('pt-BR', { timeZone: 'UTC' })`
 * produz '20/10/2026' — mas custa ~200 µs e reconstrói um `Date` por linha.
 * A string ISO já está em UTC, então fatiar dá o mesmo resultado, sem fuso e
 * sem custo.
 *
 * @param {string} dataISO 'YYYY-MM-DD'
 * @returns {string} 'dd/mm/aaaa' (string vazia se a data não for ISO)
 */
export const dataBRDeDataISO = (dataISO) => {
    const partes = partesDataISO(dataISO);
    if (!partes) return '';
    return `${partes.dia}/${partes.mes}/${partes.ano}`;
};

/**
 * Os últimos N meses (do mais antigo para o mais recente), com ano e mês
 * corretos — inclusive na virada de ano.
 *
 * O código anterior montava o rótulo com `today.getFullYear()` para **todos**
 * os seis meses, o que rotulava errado os meses do ano anterior e, pior,
 * fazia as coletas desses meses caírem fora do gráfico (a chave do balde não
 * batia com a chave calculada a partir da data da coleta).
 *
 * @param {number} quantidade quantos meses
 * @param {Date} referencia data de referência (padrão: hoje)
 * @returns {Array<{ano: number, mes: number}>} do mais antigo ao mais recente
 */
export const ultimosMeses = (quantidade, referencia = new Date()) => {
    const meses = [];
    for (let i = quantidade - 1; i >= 0; i -= 1) {
        const d = new Date(referencia.getFullYear(), referencia.getMonth() - i, 1);
        meses.push({ ano: d.getFullYear(), mes: d.getMonth() + 1 });
    }
    return meses;
};
