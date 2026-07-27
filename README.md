# cleveft-transcription-service

Turns a lecture recording into a transcript, structured notes and searchable
vector chunks. Also the retrieval endpoint every other service uses to fetch
lecture context.

Runs on port `8082` and owns the `transcription` schema. All client traffic goes
through the gateway on `8080`.

Spring Boot 3.3.5, Java 21, PostgreSQL with `pgvector`.

## The pipeline

An upload returns immediately with a lecture ID; processing happens on a
background worker and the client polls `/{lectureId}/status`.

```
upload → speech-to-text → note structuring → chunking → embedding → pgvector
```

Each stage is recorded, so a lecture that fails part-way can be resumed with
`/{lectureId}/retry` rather than re-uploaded.

There is a second entry point for material that was never spoken: a PDF posted
to `/documents` skips speech-to-text, has its text extracted with PDFBox, and
joins the same pipeline at the note-structuring stage. Slide decks and handouts
therefore become queryable exactly like a recording.

`transcription.chunks` is the only vector table in the system. The query and
exam-prep services retrieve context by calling `/search` here, never by reading
pgvector themselves.

## API

Base path `/api/v1/transcriptions`.

| Method   | Path                    | Description                                          |
| -------- | ----------------------- | ---------------------------------------------------- |
| `POST`   | `/`                     | Upload lecture audio (multipart); returns a lecture ID |
| `POST`   | `/documents`            | Upload a PDF (multipart); same pipeline, no audio      |
| `GET`    | `/`                     | List the student's lectures                          |
| `GET`    | `/stats`                | Counts for the dashboard                             |
| `GET`    | `/usage`                | Recordings used this month against the plan quota    |
| `POST`   | `/search`               | Vector search over chunks — used by other services   |
| `GET`    | `/{lectureId}`          | A lecture with its transcript and notes              |
| `GET`    | `/{lectureId}/status`   | Pipeline progress, polled after upload               |
| `PATCH`  | `/{lectureId}`          | Rename, or assign to a course                        |
| `POST`   | `/{lectureId}/retry`    | Re-run a failed pipeline                             |
| `DELETE` | `/{lectureId}`          | Delete a lecture and its chunks                      |

## Configuration

Settings live in `src/main/resources/application.yml`. See `.env.example`.

| Variable                  | Default                    | Meaning                                        |
| ------------------------- | -------------------------- | ---------------------------------------------- |
| `GOOGLE_API_KEY`          | —                          | Gemini credentials                             |
| `GEMINI_STT_MODEL`        | `gemini-3.5-flash`         | Speech-to-text; compose overrides to flash-lite |
| `GEMINI_NOTES_MODEL`      | `gemini-3.5-flash`         | Transcript → structured notes                  |
| `GEMINI_EMBEDDING_MODEL`  | `gemini-embedding-001`     | Chunk embeddings                               |
| `AUTH_SERVICE_URL`        | `http://localhost:8084`    | Reads the student's plan before an upload      |
| `MAX_UPLOAD_SIZE`         | `200MB`                    | A one-hour lecture lands well under this       |
| `RETAIN_AUDIO`            | `true`                     | Keep the source audio after processing         |
| `AUDIO_STORAGE_PATH`      | `./data/audio`             | Where it is kept                               |

Two notes on these:

- **Model allocation is deliberate.** Every Gemini model has its own free-tier
  quota pool, so speech-to-text and note structuring are put on different models
  — otherwise importing a PDF could be refused because a lecture was transcribed
  an hour earlier. Embeddings have their own pool again.
- **`AUTH_SERVICE_URL` matters.** Left at its default inside a container it
  points at this service itself, the plan lookup fails, and the quota check
  fails open — every account then behaves as unlimited and the free tier stops
  meaning anything.

The embedding width is fixed at 768 and must equal the `vector(n)` column width
in `cleveft-infra/init.sql`.

## Running it

With the rest of the stack:

```bash
cd ../cleveft-infra
docker compose --profile services up -d --build
```

On its own, against the shared database:

```bash
docker compose up -d          # in cleveft-infra, for postgres
mvn spring-boot:run
```

Requires Java 21 and a PostgreSQL instance with `pgvector` enabled, carrying the
`transcription` schema from `cleveft-infra/init.sql`. Hibernate runs with
`ddl-auto: none`, so this service never creates or alters a table.
