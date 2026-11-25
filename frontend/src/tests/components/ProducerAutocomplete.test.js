import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ProducerAutocomplete from '../../components/ProducerAutocomplete';

const PRODUTORES = [
    { id: 1, nome: 'Ana Ribeiro' },
    { id: 2, nome: 'Bruno Alves' },
    { id: 3, nome: 'Carla Souza' },
];

const campo = () => screen.getByLabelText('Nome do produtor');
const seta = () => screen.getByLabelText('Mostrar sugestões');

// As sugestões são renderizadas por portal em document.body, então saem da
// árvore do container do componente — por isso as buscas são feitas no
// document inteiro, e não em `container`.
const sugestoes = () => screen.queryAllByRole('listitem');

describe('ProducerAutocomplete', () => {
    let onSelectExisting;
    let onCreateNew;

    beforeEach(() => {
        onSelectExisting = jest.fn();
        onCreateNew = jest.fn();
    });

    test('mostra o produtor já resolvido e não sinaliza pendência', () => {
        render(
            <ProducerAutocomplete
                value={{ id: 1, nome: 'Ana Ribeiro' }}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        expect(campo()).toHaveValue('Ana Ribeiro');
        expect(screen.queryByText('Pendente')).not.toBeInTheDocument();
    });

    test('sinaliza Pendente enquanto a linha ainda não tem produtor', () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        expect(campo()).toHaveValue('');
        expect(screen.getByText('Pendente')).toBeInTheDocument();
    });

    test('abre a lista de sugestões ao focar o campo', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());

        await waitFor(() => expect(screen.getByText('Ana Ribeiro')).toBeInTheDocument());
        expect(screen.getByText('Bruno Alves')).toBeInTheDocument();
    });

    test('a seta alterna a abertura da lista', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.click(seta());
        await waitFor(() => expect(sugestoes()).toHaveLength(3));

        fireEvent.click(seta());
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
    });

    test('filtra as sugestões conforme o usuário digita', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'br' } });

        await waitFor(() => expect(screen.getByText('Bruno Alves')).toBeInTheDocument());
        expect(screen.queryByText('Ana Ribeiro')).not.toBeInTheDocument();
    });

    test('limita a lista a 8 sugestões para não despejar o producers inteiro', async () => {
        const muitos = Array.from({ length: 30 }, (_, i) => ({ id: i, nome: `Produtor ${i}` }));
        render(
            <ProducerAutocomplete
                value={null}
                producers={muitos}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());
        await waitFor(() => expect(sugestoes()).toHaveLength(8));
    });

    test('oferece criar produtor novo quando o nome não existe na lista', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: ' Novo Fazendeiro  ' } });

        expect(await screen.findByText('Criar novo produtor: "Novo Fazendeiro"')).toBeInTheDocument();
        expect(screen.queryByText('Nenhum produtor encontrado')).not.toBeInTheDocument();
    });

    test('não oferece criar quando o nome digitado já existe (evita duplicata)', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Ana Ribeiro' } });

        await waitFor(() => expect(screen.getByText('Ana Ribeiro')).toBeInTheDocument());
        expect(screen.queryByRole('button', { name: /Criar novo produtor/ })).not.toBeInTheDocument();
    });

    test('avisa que nada foi encontrado quando não há lista nem nome digitado', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={[]}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());

        expect(await screen.findByText('Nenhum produtor encontrado')).toBeInTheDocument();
    });

    test('escolher uma sugestão preenche o campo e devolve o produtor ao pai', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());
        fireEvent.click(await screen.findByText('Carla Souza'));

        expect(campo()).toHaveValue('Carla Souza');
        expect(onSelectExisting).toHaveBeenCalledWith({ id: 3, nome: 'Carla Souza' });
        // fecha a lista após escolher
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
    });

    test('Enter escolhe a única sugestão quando ela é um produtor existente', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Carla Souza' } });
        await screen.findByText('Carla Souza');
        fireEvent.keyDown(campo(), { key: 'Enter' });

        expect(onSelectExisting).toHaveBeenCalledWith({ id: 3, nome: 'Carla Souza' });
        expect(campo()).toHaveValue('Carla Souza');
    });

    test('Enter num nome inexistente cria o produtor com o texto digitado', async () => {
        onCreateNew.mockResolvedValue({ id: 9, nome: 'Dona Neuza' });
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Dona Neuza' } });
        await screen.findByRole('button', { name: /Criar novo produtor/ });
        fireEvent.keyDown(campo(), { key: 'Enter' });

        await waitFor(() => expect(campo()).toHaveValue('Dona Neuza'));
        expect(onCreateNew).toHaveBeenCalledWith('Dona Neuza');
    });

    test('criar pelo botão mostra o produtor criado e fecha a lista', async () => {
        onCreateNew.mockResolvedValue({ id: 9, nome: 'Dona Neuza' });
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Dona Neuza' } });
        fireEvent.click(await screen.findByRole('button', { name: /Criar novo produtor/ }));

        await waitFor(() => expect(campo()).toHaveValue('Dona Neuza'));
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
    });

    test('mantém o nome digitado e mostra o erro quando a criação falha', async () => {
        onCreateNew.mockRejectedValue(new Error('E-mail já cadastrado'));
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Dona Neuza' } });
        fireEvent.click(await screen.findByRole('button', { name: /Criar novo produtor/ }));

        expect(await screen.findByText('E-mail já cadastrado')).toBeInTheDocument();
        // o que o usuário digitou não é perdido — ele pode corrigir e tentar de novo
        expect(campo()).toHaveValue('Dona Neuza');
        expect(seta()).not.toBeDisabled();
    });

    test('se a criação não devolver o produtor, mantém o nome digitado', async () => {
        onCreateNew.mockResolvedValue(undefined);
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Dona Neuza' } });
        fireEvent.click(await screen.findByRole('button', { name: /Criar novo produtor/ }));

        await waitFor(() => expect(sugestoes()).toHaveLength(0));
        expect(campo()).toHaveValue('Dona Neuza');
    });

    test('Enter com campo vazio não faz nada', async () => {
        onCreateNew.mockResolvedValue({ id: 9, nome: 'Dona Neuza' });
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());
        await waitFor(() => expect(sugestoes()).toHaveLength(3));
        fireEvent.keyDown(campo(), { key: 'Enter' });

        expect(onCreateNew).not.toHaveBeenCalled();
        expect(onSelectExisting).not.toHaveBeenCalled();
        // a lista continua aberta: a tecla Enter não deve atrapalhar a navegação
        expect(sugestoes()).toHaveLength(3);
    });

    test('Enter com várias sugestões não escolhe nenhuma arbitrariamente', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'a' } });
        await waitFor(() => expect(sugestoes().length).toBeGreaterThan(1));
        fireEvent.keyDown(campo(), { key: 'Enter' });

        expect(onSelectExisting).not.toHaveBeenCalled();
        expect(onCreateNew).not.toHaveBeenCalled();
    });

    test('o erro de criação some quando o usuário edita o campo de novo', async () => {
        onCreateNew.mockRejectedValue(new Error('E-mail já cadastrado'));
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.change(campo(), { target: { value: 'Dona Neuza' } });
        fireEvent.click(await screen.findByRole('button', { name: /Criar novo produtor/ }));
        expect(await screen.findByText('E-mail já cadastrado')).toBeInTheDocument();

        fireEvent.change(campo(), { target: { value: 'Dona Neuza S' } });
        expect(screen.queryByText('E-mail já cadastrado')).not.toBeInTheDocument();
    });

    test('Escape fecha a lista sem selecionar nada', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());
        await waitFor(() => expect(sugestoes()).toHaveLength(3));

        fireEvent.keyDown(campo(), { key: 'Escape' });
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
        expect(onSelectExisting).not.toHaveBeenCalled();
    });

    test('clicar fora do campo fecha a lista', async () => {
        render(
            <div>
                <ProducerAutocomplete
                    value={null}
                    producers={PRODUTORES}
                    onSelectExisting={onSelectExisting}
                    onCreateNew={onCreateNew}
                />
                <button type="button">Outra área da tela</button>
            </div>
        );

        fireEvent.focus(campo());
        await waitFor(() => expect(sugestoes()).toHaveLength(3));

        fireEvent.mouseDown(screen.getByRole('button', { name: 'Outra área da tela' }));
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
    });

    test('clicar dentro da lista não a fecha antes do onClick selecionar', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        fireEvent.focus(campo());
        const item = await screen.findByText('Ana Ribeiro');

        // o mousedown dentro do dropdown não pode fechar a lista, senão o
        // onClick do item jamais dispararia e a seleção seria impossível
        fireEvent.mouseDown(item);
        expect(screen.getByText('Ana Ribeiro')).toBeInTheDocument();

        fireEvent.click(item);
        expect(onSelectExisting).toHaveBeenCalledWith({ id: 1, nome: 'Ana Ribeiro' });
    });

    test('desabilitado não abre lista nem deixa editar', async () => {
        render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
                disabled
            />
        );

        expect(campo()).toBeDisabled();
        expect(seta()).toBeDisabled();

        fireEvent.focus(campo());
        fireEvent.click(seta());
        await waitFor(() => expect(sugestoes()).toHaveLength(0));
    });

    test('acompanha a troca de produtor feita fora do componente', () => {
        const { rerender } = render(
            <ProducerAutocomplete
                value={null}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );
        expect(campo()).toHaveValue('');

        rerender(
            <ProducerAutocomplete
                value={{ id: 2, nome: 'Bruno Alves' }}
                producers={PRODUTORES}
                onSelectExisting={onSelectExisting}
                onCreateNew={onCreateNew}
            />
        );

        expect(campo()).toHaveValue('Bruno Alves');
        expect(screen.queryByText('Pendente')).not.toBeInTheDocument();
    });
});
