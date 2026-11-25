package com.erp.ocr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterUserDto(
                @NotBlank @Email String email,
                // Sem @Size o ADMIN podia criar usuario com senha de 1 caractere.
                // O BCrypt ainda trunca silenciosamente o que passar de 72 bytes,
                // tornando irrelevante o sufixo da senha sem nenhum aviso.
                @NotBlank
                @Size(min = 12, max = 72, message = "A senha deve ter entre 12 e 72 caracteres.")
                String senha,
                @NotBlank @Size(max = 200) String nome,
                com.erp.ocr.model.Role role) {
}
