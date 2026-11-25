package com.erp.ocr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.UserRepository;


@Configuration
public class DataSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataSeeder.class);

    /**
     * Nome da organização criada no primeiro boot. CASADO com o backfill da
     * migration V4__tenancy.sql: se este nome mudar aqui e la, o seed e a
     * migration passam a apontar para organizações diferentes.
     */
    private static final String ORGANIZACAO_INICIAL = "Acme Supply Co.";

    @org.springframework.beans.factory.annotation.Value("${admin.initial-email:admin@erp-demo.local}")
    private String adminEmail;

    @org.springframework.beans.factory.annotation.Value("${admin.initial-password}")
    private String adminPassword;

    private final UserRepository usuarioRepository;
    private final com.erp.ocr.repository.OrganizationRepository organizacaoRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository usuarioRepository,
            com.erp.ocr.repository.OrganizationRepository organizacaoRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.organizacaoRepository = organizacaoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        seedAdminUser();
        // Os dados de DEMONSTRAÇÃO saem daqui de propósito: foram movidos para o
        // DemoDataGenerator, que só roda sob o perfil Spring "demo". Gerar
        // milhares de registros a cada subida da aplicação atrasaria o boot e,
        // pior, colocaria dado fictício em produção bastou ligar a variável
        // SEED_DEMO_DATA. Ver DemoDataGenerator.
    }

    private void seedAdminUser() {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                    "admin.initial-password (ADMIN_INITIAL_PASSWORD) não pode estar vazio. "
                            + "Defina uma senha forte para o usuário administrador inicial.");
        }
        if (usuarioRepository.findByEmail(adminEmail).isEmpty()) {
            // A organização precisa existir ANTES do admin: a coluna é NOT NULL.
            com.erp.ocr.model.Organization organizacao = organizacaoRepository
                    .findByNome(ORGANIZACAO_INICIAL)
                    .orElseGet(() -> organizacaoRepository
                            .save(new com.erp.ocr.model.Organization(ORGANIZACAO_INICIAL)));

            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setNome("Administrador");
            admin.setSenha(passwordEncoder.encode(adminPassword));
            admin.setRole(com.erp.ocr.model.Role.ADMIN);
            admin.setOrganization(organizacao);
            usuarioRepository.save(admin);
            logger.info("Admin user seeded: {} (password from ADMIN_INITIAL_PASSWORD env var)", adminEmail);
        }
    }

}
