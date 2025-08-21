# ScannerProjectV2 (Java Rewrite)

A Java rewrite and evolution of the original Python-based scanner/renaming utility.  
This application monitors one or more directories for newly created (or modified) files, validates or extracts barcode-like identifiers from their names (or content—future feature), and then renames/moves them into a normalized pattern for downstream processing or archival.

> Status: Early rewrite phase. Functionality is being ported and modularized. Expect rapid iteration and potential breaking changes until v0.1.0 is tagged.

---

## Goals

- Achieve feature parity (and then improvements) over the original Python implementation.
- Provide a robust, testable, and extensible file monitoring + rename pipeline.
- Enforce consistent barcode validation and formatting rules.
- Offer configuration-driven behavior (e.g., watch folders, naming templates, filters).
- Improve observability (structured logs, dry-run, metrics—later phase).

---

## Key Features (Planned / Implemented)

| Feature | Status | Notes |
|---------|--------|-------|
| Directory watch (Java NIO WatchService) | In progress | Initial implementation watching a single directory. |
| File rename strategy abstraction | Planned | Strategy interface for future variations. |
| Barcode validation & normalization utilities | In progress | Refactoring into dedicated utility/service. |
| Collision handling (duplicate target names) | Planned | Add counter or timestamp suffix logic. |
| Configurable patterns (e.g. "{barcode}_{original}") | Planned | Using CLI args / properties file. |
| Dry run mode | Planned | Log intended actions without performing them. |
| Logging via SLF4J + backend (Logback) | Planned | Replace System.out usage. |
| Unit & integration tests | Planned | JUnit 5 + TempDir & maybe Jimfs. |
| Parallel processing queue | Future | For high-throughput environments. |
| Metrics / health endpoints | Future | Micrometer or JMX beans. |

Legend: Implemented | In progress | Planned | Future

---

## Quick Start

(Adjust once modules & packaging finalized.)

### Prerequisites
- Java 17+ (consider adopting 21 LTS if feasible)
- Maven 3.9+ (or just use the Maven Wrapper once added)
- Git

### Clone & Build
```bash
git clone https://github.com/BlueWaterTurtle/ScannerProjectV2.git
cd ScannerProjectV2
mvn clean package
```

### Run (Prototype)
```bash
# Example (will change once Main class & CLI are added)
java -jar target/scannerprojectv2-<VERSION>.jar \
  --watchDir=/path/to/incoming \
  --pattern="{barcode}_{original}" \
  --dryRun=false
```

(If not yet implemented, these flags are placeholders — see Roadmap.)

---

## Configuration (Design Draft)

| Option | Source | Default | Description |
|--------|--------|---------|-------------|
| watchDir | CLI arg / env / properties | (required) | Directory to monitor. |
| recursive | properties / CLI | false | Whether to watch subdirectories (custom walker needed; WatchService is per dir). |
| pattern | properties / CLI | {barcode}_{original} | Output filename template tokens: {barcode}, {original}, {timestamp}. |
| collisionStrategy | properties | append-counter | One of: append-counter, overwrite, skip. |
| allowedExtensions | properties | * | Comma-separated filter of file suffixes. |
| dryRun | CLI / properties | false | If true, do not perform moves. |
| logLevel | properties | INFO | Logging verbosity. |
| barcode.minLength | properties | 6 | Minimum accepted length. |
| barcode.maxLength | properties | 32 | Maximum accepted length. |
| barcode.allowedCharset | properties | A-Z0-9 | Validation whitelist (regex-like). |

A simple `application.properties` (future):
```
watchDir=/data/incoming
pattern={barcode}_{timestamp}_{original}
collisionStrategy=append-counter
allowedExtensions=pdf,jpg,png
```

---

## Architecture (Target)

```
+--------------------+
| FileWatcherService |  -> Wraps WatchService, emits FileEvents
+---------+----------+
          |
          v
  +---------------+     +-------------------+
  | Event Queue   | --> | FileProcessor     | -> orchestrates validation & rename
  +-------+-------+     +---------+---------+
          |                        |
          v                        v
  +---------------+        +---------------+
  | BarcodeParser |        | RenameStrategy|
  +---------------+        +---------------+
                                   |
                                   v
                           +---------------+
                           | FileRenamer   |
                           +---------------+
```

Modular separation:
- FileWatcherService: Low-level OS event integration.
- BarcodeParser / Validator: All logic for detecting & sanitizing codes.
- RenameStrategy: Decides target filename pattern & collision handling.
- FileRenamer: Executes atomic move, error handling, retries.
- (Future) MetricsPublisher / HealthReporter.

---

## Barcode Handling Philosophy

1. Extract candidate barcode from (a) filename substring, (b) optional future reading of file metadata or header.
2. Normalize: uppercase, trim, remove disallowed characters.
3. Validate against length and charset constraints.
4. Provide clear error/warn logs if rejected to aid operators.

---

## Error & Collision Handling (Planned)

- If target filename exists:
  - append-counter: `NAME.ext`, `NAME (1).ext`, `NAME (2).ext`
  - or timestamp variant: `NAME_20250101T120000Z.ext`
- If file is still being written (size still changing), implement a short backoff / retry up to N attempts.
- If barcode invalid -> move file into a `rejected/` subdirectory (configurable) with a reason logged.

---

## Testing Strategy (Planned)

- Unit tests:
  - Barcode validation (edge cases: empty, too long, invalid chars).
  - Rename pattern rendering.
- Integration tests:
  - Simulated file drop in a TempDir; assert final renamed state.
- (Optional) Property-based tests for random barcode strings (jqwik).
- Mutation testing (future) with PIT for robustness.

---

## Development Roadmap

| Milestone | Description |
|-----------|-------------|
| 0.1.0-alpha | Basic WatchService + simple rename of files with already-valid barcodes. |
| 0.1.0 | Add configuration system, collision handling, tests, logging framework. |
| 0.2.0 | Dry run, metrics skeleton, improved error handling. |
| 0.3.0 | Recursive watch (manual registry), plugin-friendly strategies. |
| 0.4.0 | Performance tuning, parallel processing, Docker packaging. |
| 1.0.0 | Stable, feature parity + documented extensibility. |

(Adjust based on evolving needs.)

---

## Parity Checklist vs Python Version

Create/update this as you port features:

- [ ] Identical barcode validation rules
- [ ] Equivalent directory traversal scope
- [ ] Same rename pattern defaults
- [ ] Error handling semantics (e.g., what constitutes a reject)
- [ ] Logging clarity / mapping to original verbosity
- [ ] Performance baseline (files/sec)
- [ ] Configuration options coverage
- [ ] Collision strategy equivalence
- [ ] Unit/integration test coverage target (%)
- [ ] Documentation completeness

Keep a `parity.md` for detailed mapping (optional future file).

---

## Contributing

1. Fork & branch: `feature/<short-description>`
2. Enable pre-commit checks (once we add Spotless / Checkstyle).
3. Write/update tests for any new logic.
4. Ensure `mvn verify` passes cleanly (no new warnings).
5. Open a PR with:
   - Summary
   - Implementation notes
   - Any deviation from Python behavior
   - Screens/log excerpts if relevant

We’ll later add:
- ISSUE templates
- PR template
- GitHub Actions CI (build + test + lint)

---

## Logging & Observability (Planned)

- SLF4J façade with Logback.
- Log fields: event=rename, source=..., target=..., barcode=..., durationMs=..., outcome=SUCCESS/FAIL.
- Potential future: counters (processed_total, errors_total), export via Micrometer.

---

## Security & Safety Considerations

- Strict whitelist on filename characters to avoid path traversal.
- Atomic move where supported.
- Avoid processing hidden / temporary files (e.g., those starting with `.~` or ending with `.part`).
- Consider file size thresholds if scanning content in future (prevent memory abuse).

---

## FAQ (Draft)

Q: Why Java?  
A: Strong concurrency primitives, easy packaging into a single JAR, improved static typing over the original Python implementation for maintainability.

Q: Why not use a higher-level framework?  
A: Keeping footprint minimal; plain Java + small libs keeps deployment simple.

Q: Can I watch multiple directories?  
A: Planned. Interim workaround: run multiple instances (with distinct config).

---

## Future Enhancements / Ideas

- GUI status dashboard (JavaFX) or web UI.
- Plugin system (ServiceLoader) for custom barcode extraction.
- Remote management (REST / gRPC).
- Cloud storage integration (move renamed files to S3/Azure Blob).
- Machine vision barcode extraction from images (if scope expands).

---

## Project Status Badges (Placeholders)

(Replace once CI & license added.)

```
Build: (coming soon)
License: (choose and add)
Coverage: (after tests)
```

---

## License

Choose a license (MIT / Apache-2.0 recommended for permissive use) and add a LICENSE file.  
Placeholder: © 2025 Your Name / Organization.

---

## Acknowledgements

- Original Python implementation authors.
- Java NIO WatchService documentation & community examples.

---

## Getting Help

For now, open a GitHub Issue with:
- Environment (OS, Java version)
- Steps to reproduce
- Expected vs actual behavior
- Logs (sanitized)

---

## Next Steps (Actionable TODOs)

- [ ] Introduce standard Maven src layout & packages
- [ ] Add Maven Wrapper
- [ ] Add logging framework dependency
- [ ] Implement Main entrypoint + CLI parsing (picocli?)
- [ ] Write initial unit tests
- [ ] Add GitHub Actions workflow
- [ ] Add LICENSE + basic badges
- [ ] Flesh out collision handling logic
- [ ] Implement configuration loader

---

Let me know if you’d like a streamlined or a minimal version of this README, or if you want me to generate supporting files (LICENSE, CONTRIBUTING, GitHub Actions workflow, pom.xml enhancements).
