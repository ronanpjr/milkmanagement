package com.erp.ocr.gateway;

import com.erp.ocr.config.OrganizationContext;
import com.erp.ocr.dto.InvoiceGenerationRequestDto;
import com.erp.ocr.dto.InvoiceRecordDto;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.SupplierRepository;
import jakarta.persistence.EntityNotFoundException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Default mock/demo implementation of {@link InvoicingGateway}.
 * <p>
 * Reads tenant-scoped collection entries (or generates simulated entries when
 * requested) and exports them to a generic XLSX invoice summary without
 * exposing any proprietary fiscal rules or external credentials.
 */
@Service
public class MockInvoicingGateway implements InvoicingGateway {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CollectionEntryRepository collectionEntryRepository;
    private final SupplierRepository supplierRepository;
    private final OrganizationContext organizationContext;

    public MockInvoicingGateway(
            CollectionEntryRepository collectionEntryRepository,
            SupplierRepository supplierRepository,
            OrganizationContext organizationContext) {
        this.collectionEntryRepository = collectionEntryRepository;
        this.supplierRepository = supplierRepository;
        this.organizationContext = organizationContext;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceRecordDto> generateInvoiceRecords(InvoiceGenerationRequestDto request) {
        Supplier supplier = supplierRepository
                .findVisiveisByIdIn(List.of(request.supplierId()), organizationContext.organizacaoId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Supplier not found: " + request.supplierId()));

        String taxId = supplier.getInscricaoEstadual();
        if (taxId == null || taxId.isBlank()) {
            throw new IllegalStateException("Supplier does not have a registered Tax ID.");
        }

        if (request.isUseSimulatedData()) {
            return generateSimulatedRecords(
                    taxId,
                    request.year(),
                    request.month(),
                    request.getSimulatedDaysCountOrDefault(),
                    request.getMinQuantityOrDefault(),
                    request.getMaxQuantityOrDefault());
        }

        List<CollectionEntry> entries = collectionEntryRepository.findVisiveisPorSupplierEMes(
                supplier.getId(), request.year(), request.month(), organizationContext.organizacaoId());

        return entries.stream()
                .sorted(Comparator.comparing(CollectionEntry::getData))
                .map(entry -> InvoiceRecordDto.actual(
                        taxId,
                        entry.getData().format(DATE_FORMAT),
                        entry.getQuantidadeLitros()))
                .collect(Collectors.toList());
    }

    private List<InvoiceRecordDto> generateSimulatedRecords(
            String taxId, int year, int month, int daysCount, int minQuantity, int maxQuantity) {
        if (minQuantity > maxQuantity) {
            throw new IllegalArgumentException("Minimum quantity cannot exceed maximum quantity.");
        }

        YearMonth yearMonth = YearMonth.of(year, month);
        int daysInMonth = yearMonth.lengthOfMonth();
        int targetDays = Math.min(daysCount, daysInMonth);

        Set<Integer> selectedDays = new TreeSet<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (selectedDays.size() < targetDays) {
            selectedDays.add(random.nextInt(1, daysInMonth + 1));
        }

        List<InvoiceRecordDto> records = new ArrayList<>(targetDays);
        for (Integer day : selectedDays) {
            LocalDate date = LocalDate.of(year, month, day);
            int quantity = random.nextInt(minQuantity, maxQuantity + 1);
            records.add(InvoiceRecordDto.estimated(taxId, date.format(DATE_FORMAT), quantity));
        }
        return records;
    }

    @Override
    public byte[] exportToXlsx(List<InvoiceRecordDto> records) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Invoice Records");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            Row header = sheet.createRow(0);
            String[] columns = { "SUPPLIER_TAX_ID", "RECEIPT_DATE", "QUANTITY", "REFERENCE_CODE", "ESTIMATED" };
            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (InvoiceRecordDto item : records) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(item.supplierTaxId());
                row.createCell(1).setCellValue(item.receiptDate());
                row.createCell(2).setCellValue(item.quantity());
                row.createCell(3).setCellValue(item.referenceCode());
                row.createCell(4).setCellValue(item.estimated() ? "YES" : "NO");
            }

            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
