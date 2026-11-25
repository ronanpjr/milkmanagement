# Estágio 1: Build da aplicação com Maven e JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build

# Define o diretório de trabalho dentro do container
WORKDIR /app

# Copia os arquivos de configuração do Maven para aproveitar o cache de dependências
COPY .mvn/ .mvn
COPY mvnw pom.xml ./

# Os filtros do SpotBugs entram junto do pom.xml, e nao com o src: sao
# configuracao de build, referenciados pelo pom. Se viessem depois, cada
# edicao em um filtro invalidaria a camada de dependency:go-offline e faria o
# build da imagem rebaixar tudo sem necessidade. Sem eles, o ./mvnw package
# do estagio seguinte falha com "Could not find resource
# 'spotbugs-filter.xml'" — o plugin roda no perfil JDK 21, que a imagem usa.
COPY spotbugs-filter.xml spotbugs-exclude.xml ./

# Baixa as dependências do projeto
RUN ./mvnw dependency:go-offline

# Copia o código fonte da aplicação
COPY src ./src

# --- A CORREÇÃO CRÍTICA ESTÁ AQUI ---
# Define a variável de ambiente MAVEN_OPTS para forçar a codificação UTF-8
# para a JVM que executa o Maven.
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

# Compila o projeto e gera o arquivo .jar, pulando os testes
# Os testes NÃO rodam aqui: o workflow "Backend CI" roda a suíte antes de
# buildar a imagem, e repetir a suíte dentro do build só duplicaria o tempo
# sem aumentar a garantia.
RUN ./mvnw package -DskipTests

# Estágio 2: Criação da imagem final de execução
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Cria um grupo e um usuário não-root para rodar a aplicação
RUN addgroup -S spring && adduser -S spring -G spring

# Copia o .jar gerado no estágio anterior para a imagem final
COPY --from=build /app/target/*.jar /app/app.jar

# Define o usuário que executará a aplicação
USER spring:spring

# Expõe a porta em que a aplicação Spring Boot roda
EXPOSE 8080

# Healthcheck do container, usado pelo `docker compose ps` e pelo
# `depends_on: service_healthy` de quem subir a stack na mão.
#
# O Actuator/management sobe na 8081 (management.server.port); a 8080 é a API.
# Consultar /actuator/health na 8080 devolvia 404 e deixava o container
# "unhealthy" mesmo com a aplicação no ar.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q -O- "http://127.0.0.1:${MANAGEMENT_PORT:-8081}/actuator/health" | grep -q '"status":"UP"' || exit 1

# Comando para iniciar a aplicação quando o container for executado
CMD ["java", "-jar", "app.jar"]
