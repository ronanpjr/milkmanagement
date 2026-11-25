package com.erp.ocr.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.erp.ocr.dto.CreateSettlementDto;
import com.erp.ocr.dto.UpdateSettlementDto;
import com.erp.ocr.dto.MonthlySettlementResponseDto; // Importar
import com.erp.ocr.service.MonthlySettlementService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/settlements")
public class MonthlySettlementController {

    private static final java.util.Set<String> SORT_FIELDS = java.util.Set.of(
            "dataInicio", "dataFim", "totalLitros", "totalLiquido", "statusPagamento", "id");

    @Autowired
    private MonthlySettlementService fechamentoService;

    @PostMapping
    public ResponseEntity<MonthlySettlementResponseDto> criar(@RequestBody @Valid CreateSettlementDto dto) {
        MonthlySettlementResponseDto novoFechamento = fechamentoService.criarFechamento(dto);
        URI location = URI.create("/api/settlements/" + novoFechamento.id());
        return ResponseEntity.created(location).body(novoFechamento);
    }

    @PutMapping("/{id}/payment")
    public ResponseEntity<MonthlySettlementResponseDto> atualizarPagamento(@PathVariable Long id,
            @RequestBody @Valid UpdateSettlementDto dto) {
        MonthlySettlementResponseDto fechamentoAtualizado = fechamentoService.atualizarPagamento(id, dto);
        return ResponseEntity.ok(fechamentoAtualizado);
    }

    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<MonthlySettlementResponseDto>> listarTodos(
            @RequestParam(required = false) Long produtorId,
            @RequestParam(required = false) java.time.LocalDate inicio,
            @RequestParam(required = false) java.time.LocalDate fim,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "dataInicio") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        com.erp.ocr.util.PaginationUtil.validateSortField(sortBy, SORT_FIELDS);
        org.springframework.data.domain.Sort sort = sortDir.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.by(sortBy).ascending()
                : org.springframework.data.domain.Sort.by(sortBy).descending();
        org.springframework.data.domain.Pageable pageable = com.erp.ocr.util.PaginationUtil.of(page,
                size, sort);
        org.springframework.data.domain.Page<MonthlySettlementResponseDto> fechamentos = fechamentoService
                .listarComFiltros(produtorId, inicio, fim, pageable);
        return ResponseEntity.ok(fechamentos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MonthlySettlementResponseDto> buscarPorId(@PathVariable Long id) {
        MonthlySettlementResponseDto fechamento = fechamentoService.buscarPorId(id);
        return ResponseEntity.ok(fechamento);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        fechamentoService.deletar(id);
        return ResponseEntity.noContent().build();
    }
}