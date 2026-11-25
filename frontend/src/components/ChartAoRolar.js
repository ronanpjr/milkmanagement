// src/components/ChartAoRolar.js
import React, { useEffect, useRef, useState } from 'react';

/**
 * Renderiza o gráfico só quando ele chega perto da viewport.
 *
 * ## Por que
 *
 * A tela de Relatórios tem 9 gráficos. Quando os dados chegam, os 9 são
 * montados de uma vez — e cada montagem dispara a animação de entrada do
 * recharts, que recalcula o path SVG a cada frame. Nove animações
 * simultâneas disputam a main thread e o resultado é o sintoma de "choppy":
 * a animação não se move com smoothness, ela engasga.
 *
 * Aqui o gráfico só é montado quando entra (ou chega perto de entrar) na
 * viewport. O trabalho de animação deixa de ser um pico único e passa a ser
 * diluído ao longo da rolagem — e cada animação roda com a main thread
 * livre, que é o que faz ela parecer contínua.
 *
 * O espaço reservado (`altura`) é o mesmo do gráfico, para que nada "pule"
 * na rolagem.
 *
 * ## Fallback
 *
 * Sem `IntersectionObserver` (jsdom nos testes, navegadores muito antigos),
 * o conteúdo é renderizado imediatamente — o gráfico aparece, e a animação
 * acontece como antes. É degrade, não defeito.
 */
const ChartAoRolar = ({ altura = 300, margem = '250px 0px', className = '', children }) => {
    const ref = useRef(null);
    const [visivel, setVisivel] = useState(
        () => typeof IntersectionObserver === 'undefined'
    );

    useEffect(() => {
        if (visivel || typeof IntersectionObserver === 'undefined') {
            return undefined;
        }

        const observer = new IntersectionObserver(
            (entries) => {
                entries.forEach((entry) => {
                    if (entry.isIntersecting) {
                        setVisivel(true);
                        observer.disconnect();
                    }
                });
            },
            { rootMargin: margem }
        );

        if (ref.current) {
            observer.observe(ref.current);
        }

        return () => observer.disconnect();
    }, [visivel, margem]);

    return (
        <div ref={ref} className={className} style={{ height: altura }}>
            {visivel ? children : <div style={{ height: '100%' }} aria-hidden="true" />}
        </div>
    );
};

export default ChartAoRolar;
