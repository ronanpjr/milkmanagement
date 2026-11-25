package com.erp.ocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class ErpOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpOcrApplication.class, args);
    }

}
