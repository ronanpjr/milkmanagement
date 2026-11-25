package com.erp.ocr.dto;

public record InvoiceRecordDto(
        String supplierTaxId,
        String receiptDate,
        int quantity,
        String referenceCode,
        boolean estimated
) {
    public static InvoiceRecordDto actual(String supplierTaxId, String receiptDate, int quantity) {
        return new InvoiceRecordDto(supplierTaxId, receiptDate, quantity, "ERP-ACTUAL", false);
    }

    public static InvoiceRecordDto estimated(String supplierTaxId, String receiptDate, int quantity) {
        return new InvoiceRecordDto(supplierTaxId, receiptDate, quantity, "ERP-ESTIMATED", true);
    }
}
