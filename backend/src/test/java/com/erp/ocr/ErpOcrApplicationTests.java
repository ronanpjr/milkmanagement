package com.erp.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;

@SpringBootTest
class ErpOcrApplicationTests {

    @MockBean
    private AzureOcrService azureOcrService;

    @MockBean
    private GeminiOcrService geminiOcrService;

    @Test
    void contextLoads() {
    }

}
