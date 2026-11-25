package com.erp.ocr.controller;

import com.erp.ocr.dto.InvoiceGenerationRequestDto;
import com.erp.ocr.dto.InvoiceRecordDto;
import com.erp.ocr.gateway.InvoicingGateway;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/invoicing")
public class InvoicingController {

    private final InvoicingGateway invoicingGateway;

    public InvoicingController(InvoicingGateway invoicingGateway) {
        this.invoicingGateway = invoicingGateway;
    }

    @PostMapping("/generate")
    public ResponseEntity<List<InvoiceRecordDto>> generate(@Valid @RequestBody InvoiceGenerationRequestDto request) {
        return ResponseEntity.ok(invoicingGateway.generateInvoiceRecords(request));
    }

    @PostMapping("/export-xlsx")
    public ResponseEntity<byte[]> exportXlsx(@Valid @RequestBody InvoiceGenerationRequestDto request)
            throws IOException {
        List<InvoiceRecordDto> records = invoicingGateway.generateInvoiceRecords(request);
        byte[] bytes = invoicingGateway.exportToXlsx(records);

        String filename = String.format("invoice_export_%d_%02d.xlsx", request.year(), request.month());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
