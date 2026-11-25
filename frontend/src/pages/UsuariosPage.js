// src/pages/UsuariosPage.js
//
// Tela autenticada (admin) para criação de novos usuários. O registro
// público foi removido — este é o único caminho para criar contas, e
// sempre envia o Bearer token do usuário logado (o backend exige ADMIN).
import React, { useState } from 'react';

const UsuariosPage = ({ apiService, token, setNotification }) => {
    const [nome, setNome] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const [isLoading, setIsLoading] = useState(false);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setIsLoading(true);
        try {
            await apiService.register(token, nome, email, password);
            setNotification({ message: 'Usuário criado com sucesso!', type: 'success' });
            setNome('');
            setEmail('');
            setPassword('');
        } catch (err) {
            setError(err.message || 'Não foi possível criar o usuário.');
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="max-w-2xl anim-pagina">
            <h1 className="text-2xl font-bold text-gray-800 mb-6">Usuários</h1>
            <div className="bg-white p-8 rounded-xl shadow-lg w-full max-w-md">
                <h2 className="text-xl font-semibold text-gray-700 mb-6">Novo usuário</h2>
                <form onSubmit={handleSubmit}>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2" htmlFor="nome">Nome Completo</label>
                        <input type="text" id="nome" value={nome} onChange={(e) => setNome(e.target.value)} className="input-field" required />
                    </div>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2" htmlFor="email-register">Email</label>
                        <input type="email" id="email-register" value={email} onChange={(e) => setEmail(e.target.value)} className="input-field" required />
                    </div>
                    <div className="mb-6">
                        <label className="block text-gray-600 mb-2" htmlFor="password-register">Senha</label>
                        <input type="password" id="password-register" value={password} onChange={(e) => setPassword(e.target.value)} className="input-field" required />
                    </div>
                    {error && <p className="text-red-500 text-sm mb-4 text-center">{error}</p>}
                    <button type="submit" className="btn-primary w-full" disabled={isLoading}>
                        {isLoading ? 'Criando...' : 'Criar usuário'}
                    </button>
                </form>
            </div>
        </div>
    );
};

export default UsuariosPage;
