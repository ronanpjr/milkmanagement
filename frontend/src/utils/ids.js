// src/utils/ids.js
//
// Ids vindos da API são **opacos**: trate como texto, nunca como número.
//
// Motivo: os ids do backend são Snowflake (~1,22e18) e não cabem em um
// double do JavaScript (`Number.MAX_SAFE_INTEGER` = 9,007e15):
//
//   Number("1220210112017039361")  ->  1220210112017039400   // 39 dígitos perdidos
//
// O backend serializa Long como string justamente por isso (JacksonConfig).
// O cliente precisa acompanhar o contrato:
//
//   - comparar ids com `===` depois de normalizar os dois lados para string
//     (um id vindo do `select` é string, um id vindo da lista é o que o
//     backend mandou);
//   - NUNCA converter id com `Number()`/`parseInt()`: além de arredondar, o
//     id arredondado volta para a API como filtro e o servidor não encontra
//     nada — foi assim que a tela de Relatórios zerava ao selecionar um
//     produtor.
//
// `idTexto` normaliza; `mesmoId` compara.

/**
 * Normaliza um id para string, preservando os dígitos.
 * @param {string|number|null|undefined} id
 * @returns {string} '' quando não há id
 */
export const idTexto = (id) => {
    if (id === null || id === undefined) return '';
    return String(id);
};

/**
 * Compara dois ids normalizando os dois lados para string.
 * @param {string|number|null|undefined} a
 * @param {string|number|null|undefined} b
 * @returns {boolean}
 */
export const mesmoId = (a, b) => {
    const x = idTexto(a);
    return x !== '' && x === idTexto(b);
};
