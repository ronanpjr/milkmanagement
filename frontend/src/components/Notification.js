// src/components/Notification.js
import React, { useEffect } from 'react';

const Notification = ({ message, type, onDismiss }) => {
    useEffect(() => {
        if (message) {
            const timer = setTimeout(() => onDismiss(), 3000);
            return () => clearTimeout(timer);
        }
    }, [message, onDismiss]);

    if (!message) return null;

    // Só a opacidade transiciona, em vez da regra que anima todas as
    // propriedades: a notificação não tem estado oculto (aparece e some pela
    // montagem), e arrastar `box-shadow` para a transição significa repintar a
    // área sombreada a cada frame. Ver issue #35.
    //
    // (O texto desta mensagem não pode citar o nome literal da utilitária do
    // Tailwind que anima tudo: o Tailwind escaneia o código-fonte, inclusive
    // comentários, e emitia a regra para um arquivo que não a usa.)
    const baseStyle = "fixed top-5 right-5 p-4 rounded-lg shadow-lg text-white transition-opacity duration-200 transform translate-x-0 opacity-100 z-50";
    // Qualquer tipo fora de success/error/info caía no vermelho de erro, que é
    // enganoso: um aviso de 'info' aparecia como se algo tivesse falhado.
    const typeStyle = {
        success: 'bg-green-500',
        error: 'bg-red-500',
        info: 'bg-blue-500',
    }[type] || 'bg-gray-700';

    return (
        <div className={`${baseStyle} ${typeStyle}`} role="status" aria-live="polite">
            {message}
        </div>
    );
};

export default Notification;