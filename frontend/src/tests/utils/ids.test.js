import { idTexto, mesmoId } from '../../utils/ids';

describe('utils/ids', () => {
    // Os ids do backend são Snowflake (~1,22e18) e NÃO cabem em um número do
    // JavaScript. O contrato é: id é TEXTO opaco, nunca número.
    const ID_SNOWFLAKE = '1220210112017039361';

    test('idTexto preserva todos os dígitos de um id grande', () => {
        // O teste que falharia com Number(): 1220210112017039361 arredonda
        // para 1220210112017039400 e o servidor não encontra o produtor.
        expect(idTexto(ID_SNOWFLAKE)).toBe(ID_SNOWFLAKE);
        expect(Number(ID_SNOWFLAKE)).not.toBe(ID_SNOWFLAKE);
    });

    test('idTexto normaliza número e string para o mesmo texto', () => {
        expect(idTexto(7)).toBe('7');
        expect(idTexto('7')).toBe('7');
    });

    test('idTexto devolve string vazia para id ausente', () => {
        expect(idTexto(null)).toBe('');
        expect(idTexto(undefined)).toBe('');
    });

    test('mesmoId compara número e string do mesmo id', () => {
        expect(mesmoId(7, '7')).toBe(true);
        expect(mesmoId('7', 7)).toBe(true);
        expect(mesmoId('7', '8')).toBe(false);
    });

    test('mesmoId nunca considera dois ids ausentes iguais', () => {
        expect(mesmoId(null, undefined)).toBe(false);
        expect(mesmoId('', '')).toBe(false);
    });
});
