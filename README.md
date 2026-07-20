# Cleveft Transcription Service

An asynchronous media processing microservice responsible for converting recorded lecture audio into structured, tokenized textual data for downstream vector ingestion.

## 🛠️ Tech Stack
* **Framework:** Spring Boot 3.3.x
* **Vector Handling:** PGVector Extensions / Hibernate Spatial
* **Database:** PostgreSQL (Vector Storage Engine)

## 🏗️ Architecture & Processing Pipeline
This utility operates as an internal ingestion service, binding natively to its designated runtime port to process high-throughput multi-part audio streams and persist localized text embeddings.

## 🚀 Getting Started

### Prerequisites
* Java 21+ / JDK 25
* PostgreSQL Instance with `pgvector` enabled

### Setup Environment
Ensure database connections inside `application.properties` target an engine supporting vector data types, and verify that schemas match the expected structure for lecture entity persistence.

### Running the Application
```bash
# Navigate to the service directory
cd cleveft-transcription-service

# Compile and run
mvn clean spring-boot:run