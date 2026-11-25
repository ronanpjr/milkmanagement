package com.erp.ocr.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.erp.ocr.dto.SupplierRequestDto;
import com.erp.ocr.dto.SupplierResponseDto;
import com.erp.ocr.service.SupplierService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private static final java.util.Set<String> SORT_FIELDS = java.util.Set.of("nome", "linha", "ativo", "id");

    @Autowired
    private SupplierService produtorService;

    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<SupplierResponseDto>> listarTodos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "nome") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        com.erp.ocr.util.PaginationUtil.validateSortField(sortBy, SORT_FIELDS);
        org.springframework.data.domain.Sort sort = sortDir.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.by(sortBy).ascending()
                : org.springframework.data.domain.Sort.by(sortBy).descending();
        org.springframework.data.domain.Pageable pageable = com.erp.ocr.util.PaginationUtil.of(page,
                size, sort);
        org.springframework.data.domain.Page<SupplierResponseDto> produtores = produtorService.listarTodos(pageable);
        return ResponseEntity.ok(produtores);
    }

    @GetMapping("/unsettled")
    public ResponseEntity<List<SupplierResponseDto>> listarSemFechamento(
            @RequestParam java.time.LocalDate inicio,
            @RequestParam java.time.LocalDate fim) {
        List<SupplierResponseDto> produtores = produtorService.listarSemFechamento(inicio, fim);
        return ResponseEntity.ok(produtores);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponseDto> buscarPorId(@PathVariable Long id) {
        SupplierResponseDto produtor = produtorService.buscarPorId(id);
        return ResponseEntity.ok(produtor);
    }

    @PostMapping
    public ResponseEntity<SupplierResponseDto> criar(@RequestBody @Valid SupplierRequestDto dto) {
        SupplierResponseDto novoSupplier = produtorService.criar(dto);
        // Retorna o status 201 Created com a localização do novo recurso
        URI location = URI.create("/api/suppliers/" + novoSupplier.id());
        return ResponseEntity.created(location).body(novoSupplier);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SupplierResponseDto> atualizar(@PathVariable Long id,
            @RequestBody @Valid SupplierRequestDto dto) {
        SupplierResponseDto produtorAtualizado = produtorService.atualizar(id, dto);
        return ResponseEntity.ok(produtorAtualizado);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        produtorService.deletar(id);
        return ResponseEntity.noContent().build(); // Retorna 204 No Content
    }
}