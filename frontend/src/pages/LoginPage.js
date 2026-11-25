// src/pages/LoginPage.js
import React, { useState } from 'react';
import { apiService } from '../services/apiService';
import logo from '../logo.svg';

const LoginPage = ({ onLoginSuccess, onNavigate }) => {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [rememberMe, setRememberMe] = useState(false);
    const [error, setError] = useState('');
    const [isLoading, setIsLoading] = useState(false);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setIsLoading(true);
        try {
            const data = await apiService.login(email, password);
            if (!data?.token) {
                throw new Error('O servidor respondeu sem o token de autenticação.');
            }
            onLoginSuccess(data.token, data.refreshToken, rememberMe);
        } catch (err) {
            setError(err?.message || 'Não foi possível entrar. Verifique suas credenciais e tente novamente.');
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-gray-100 flex items-center justify-center p-4 pt-8">
            <div className="bg-white p-8 rounded-xl shadow-lg w-full max-w-md">
                <div className="flex justify-center items-center mb-6">
                    <img src={logo} alt="Acme Supply Co." className="h-36 w-auto object-contain" />
                </div>
                <h2 className="text-xl text-center font-semibold text-gray-700 mb-6">Supply & Production ERP</h2>
                <form onSubmit={handleSubmit}>
                    <div className="mb-4">
                        <label className="block text-gray-600 mb-2" htmlFor="email">Email</label>
                        <input type="email" id="email" value={email} onChange={(e) => setEmail(e.target.value)} className="input-field" required />
                    </div>
                    <div className="mb-6">
                        <label className="block text-gray-600 mb-2" htmlFor="password">Senha</label>
                        <input type="password" id="password" value={password} onChange={(e) => setPassword(e.target.value)} className="input-field" required />
                    </div>
                    <div className="mb-6 flex items-center">
                        <input
                            type="checkbox"
                            id="rememberMe"
                            checked={rememberMe}
                            onChange={(e) => setRememberMe(e.target.checked)}
                            className="h-4 w-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500 cursor-pointer accent-blue-600"
                        />
                        <label htmlFor="rememberMe" className="ml-2 block text-sm text-gray-900 cursor-pointer select-none">Lembrar de mim</label>
                    </div>
                    {error && <p className="text-red-500 text-sm mb-4 text-center">{error}</p>}
                    <button type="submit" className="btn-primary w-full" disabled={isLoading}>
                        {isLoading ? 'Entrando...' : 'Entrar'}
                    </button>
                </form>

            </div>
        </div>
    );
};

export default LoginPage;