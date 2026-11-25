package com.erp.ocr.gateway;

import com.erp.ocr.dto.InvoiceGenerationRequestDto;
import com.erp.ocr.dto.InvoiceRecordDto;

import java.io.IOException;
import java.util.List;

/**
 * Gateway abstraction for external fiscal / electronic invoicing providers.
 * <p>
 * Decouples core ERP operations from country-specific or vendor-specific
 * tax/invoicing APIs. Replace {@link MockInvoicingGateway} with an adapter
 * for your fiscal provider (e.g., Stripe Tax, Avalara, or local e-invoicing).
 */
public interface InvoicingGateway {

    /**
     * Builds invoice line records for a supplier in a given month/year.
     */
    List<InvoiceRecordDto> generateInvoiceRecords(InvoiceGenerationRequestDto request);

    /**
     * Exports invoice records to an XLSX spreadsheet byte payload.
     */
    byte[] exportToXlsx(List<InvoiceRecordDto> records) throws IOException;
}
