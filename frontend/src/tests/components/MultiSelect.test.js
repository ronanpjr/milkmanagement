import React, { useState } from 'react';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MultiSelect from '../../components/MultiSelect';

const OPTIONS = [
    { value: 1, label: 'Linha Sul' },
    { value: 2, label: 'Linha Norte' },
    { value: 3, label: 'Linha Central' },
];

// O componente é controlado: quem guarda o estado é o pai. O harness abaixo
// reproduz esse uso real para que o texto exibido reaja às seleções.
const Harness = ({ initial = [], onChange, ...rest }) => {
    const [value, setValue] = useState(initial);
    return (
        <MultiSelect
            options={OPTIONS}
            value={value}
            onChange={(next) => {
                setValue(next);
                if (onChange) onChange(next);
            }}
            {...rest}
        />
    );
};

// As opções ficam em uma <ul>; o gatilho repete o mesmo texto quando há
// uma única seleção, então toda consulta a opção tem de ser escopada na lista.
const lista = () => within(screen.getByRole('list'));
const opcao = (label) => lista().getByText(label).closest('li');

describe('MultiSelect', () => {
    test('mostra o placeholder e a lista fechada quando nada está selecionado', () => {
        render(<Harness placeholder="Escolha as linhas" />);

        const botao = screen.getByRole('button', { name: /Escolha as linhas/ });
        expect(botao).toBeInTheDocument();
        expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
        expect(screen.queryByText('Linha Sul')).not.toBeInTheDocument();
    });

    test('abre a lista com todas as opções ao clicar no gatilho', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));

        expect(screen.getByPlaceholderText('Buscar...')).toBeInTheDocument();
        expect(lista().getByText('Linha Sul')).toBeInTheDocument();
        expect(lista().getByText('Linha Norte')).toBeInTheDocument();
        expect(lista().getByText('Linha Central')).toBeInTheDocument();
    });

    test('marca uma opção ao clicar nela e mostra o nome dela no gatilho', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.click(lista().getByText('Linha Sul'));

        expect(screen.getByRole('button', { name: /Linha Sul/ })).toBeInTheDocument();
        // a opção continua listada e agora aparece marcada
        expect(within(opcao('Linha Sul')).getByRole('checkbox')).toBeChecked();
        expect(within(opcao('Linha Norte')).getByRole('checkbox')).not.toBeChecked();
    });

    test('desmarca a opção quando ela é clicada de novo', () => {
        const onChange = jest.fn();
        render(<Harness initial={[1]} onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: /Linha Sul/ }));
        fireEvent.click(lista().getByText('Linha Sul'));

        expect(onChange).toHaveBeenLastCalledWith([]);
        expect(screen.getByRole('button', { name: /Selecione/ })).toBeInTheDocument();
    });

    test('acumula seleções e mostra a contagem no gatilho', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.click(lista().getByText('Linha Sul'));
        fireEvent.click(lista().getByText('Linha Norte'));

        expect(screen.getByRole('button', { name: '2 selecionados' })).toBeInTheDocument();
    });

    test('mostra "Todos selecionados" quando todas as opções estão marcadas', () => {
        render(<Harness initial={[1, 2, 3]} />);
        expect(screen.getByRole('button', { name: 'Todos selecionados' })).toBeInTheDocument();
    });

    test('mostra o valor cru quando a seleção não existe mais entre as opções', () => {
        // produtor/linha removido do cadastro enquanto a filtro estava salvo
        render(<Harness initial={[999]} />);
        expect(screen.getByRole('button', { name: '999' })).toBeInTheDocument();
    });

    test('filtra as opções pelo termo digitado, sem diferenciar maiúsculas', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.change(screen.getByPlaceholderText('Buscar...'), { target: { value: 'NORTE' } });

        expect(lista().getByText('Linha Norte')).toBeInTheDocument();
        expect(lista().queryByText('Linha Sul')).not.toBeInTheDocument();
        expect(lista().queryByText('Linha Central')).not.toBeInTheDocument();
    });

    test('avisa quando a busca não encontra nenhuma opção', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.change(screen.getByPlaceholderText('Buscar...'), { target: { value: 'zzz' } });

        expect(screen.getByText('Nenhum resultado')).toBeInTheDocument();
    });

    test('"Marcar visíveis" marca apenas o que a busca deixou na tela', () => {
        const onChange = jest.fn();
        render(<Harness onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.change(screen.getByPlaceholderText('Buscar...'), { target: { value: 'Linha' } });
        fireEvent.click(screen.getByRole('button', { name: 'Marcar visíveis' }));

        expect(onChange).toHaveBeenLastCalledWith([1, 2, 3]);
        expect(screen.getByRole('button', { name: 'Todos selecionados' })).toBeInTheDocument();
    });

    test('"Desmarcar visíveis" limpa tudo quando todas as opções estão marcadas', () => {
        const onChange = jest.fn();
        render(<Harness initial={[1, 2, 3]} onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: 'Todos selecionados' }));
        fireEvent.click(screen.getByRole('button', { name: 'Desmarcar visíveis' }));

        expect(onChange).toHaveBeenLastCalledWith([]);
        expect(screen.getByRole('button', { name: /Selecione/ })).toBeInTheDocument();
    });

    test('com busca ativa, "Desmarcar visíveis" mantém o estado em vez de limpar', () => {
        // Comportamento atual (inconsistência do componente, não do teste): o
        // rótulo do botão decide por "todas as visíveis marcadas", mas o clique
        // decide por "selecionadas == visíveis". Com a busca ativa as duas
        // contagens divergem e o clique entra no ramo de marcar, preservando os
        // invisíveis. Registrado aqui para documentar, não para aprovar.
        const onChange = jest.fn();
        render(<Harness initial={[1, 2, 3]} onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: 'Todos selecionados' }));
        fireEvent.change(screen.getByPlaceholderText('Buscar...'), { target: { value: 'Sul' } });

        expect(screen.getByRole('button', { name: 'Desmarcar visíveis' })).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Desmarcar visíveis' }));

        expect(onChange).toHaveBeenLastCalledWith([1, 2, 3]);
    });

    test('"Marcar visíveis" não duplica valores já selecionados nem os invisíveis', () => {
        const onChange = jest.fn();
        render(<Harness initial={[1, 9]} onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: /selecionados|Selecione/ }));
        fireEvent.click(screen.getByRole('button', { name: 'Marcar visíveis' }));

        expect(onChange).toHaveBeenLastCalledWith([1, 9, 2, 3]);
    });

    test('"Marcar visíveis" não faz nada quando a busca não retornou nada', () => {
        const onChange = jest.fn();
        render(<Harness onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.change(screen.getByPlaceholderText('Buscar...'), { target: { value: 'zzz' } });
        fireEvent.click(screen.getByRole('button', { name: 'Marcar visíveis' }));

        expect(onChange).toHaveBeenLastCalledWith([]);
    });

    test('"Limpar" zera todas as seleções', () => {
        const onChange = jest.fn();
        render(<Harness initial={[1, 2]} onChange={onChange} />);
        fireEvent.click(screen.getByRole('button', { name: '2 selecionados' }));
        fireEvent.click(screen.getByRole('button', { name: 'Limpar' }));

        expect(onChange).toHaveBeenLastCalledWith([]);
        expect(screen.getByRole('button', { name: /Selecione/ })).toBeInTheDocument();
    });

    test('fecha a lista ao clicar fora dela', () => {
        render(
            <div>
                <Harness />
                <button type="button">Outro lugar da tela</button>
            </div>
        );
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        expect(lista().getByText('Linha Sul')).toBeInTheDocument();

        fireEvent.mouseDown(screen.getByRole('button', { name: 'Outro lugar da tela' }));
        expect(screen.queryByRole('list')).not.toBeInTheDocument();
    });

    test('mantém a lista aberta ao clicar dentro dela', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        fireEvent.mouseDown(screen.getByPlaceholderText('Buscar...'));

        expect(lista().getByText('Linha Sul')).toBeInTheDocument();
    });

    test('alterna a lista a cada clique no gatilho', () => {
        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        expect(lista().getByText('Linha Sul')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /Selecione/ }));
        expect(screen.queryByRole('list')).not.toBeInTheDocument();
    });
});
