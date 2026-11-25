package com.erp.ocr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio injetável para as regras que dependem de tempo (cota diária de OCR
 * e janelas de rate limit). Em produção é o relógio do sistema; nos testes,
 * um relógio fixo/mutável torna "virou o dia" e "a janela expirou"
 * determinísticos, sem {@code Thread.sleep}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
