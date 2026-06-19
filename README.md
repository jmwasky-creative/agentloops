# agentloops

Kotlin/JVM MVP for an external agent loop board. The system imports a Claude Code planner bundle, turns it into task cards, runs worker adapters in isolated workspaces, and forces completed work through a human review gate.

## MVP Commands

```bash
gradle run --args="import-plan examples/claude-plan/tasks.json"
gradle run --args="confirm-plan"
gradle run --args="run-ready --worker mock"
gradle run --args="board"
gradle run --args="serve --port 8787"
```

Default state lives at `.agentsloop/state.json`.

## Docker Compose

Build and start the board server:

```bash
docker compose up --build
```

The board is available at http://localhost:8787. Compose stores runtime state in
the local `.agentsloop/` directory through the `/data` volume mount.

Seed the bundled example plan and run the mock worker:

```bash
docker compose run --rm agentloops import-plan examples/claude-plan/tasks.json --state /data/state.json
docker compose run --rm agentloops confirm-plan --state /data/state.json
docker compose run --rm agentloops run-ready --worker mock --state /data/state.json
```

Run any other CLI command by replacing the command after `agentloops`:

```bash
docker compose run --rm agentloops board --state /data/state.json
```

## Tests

The MVP is covered with Kotlin tests for the loop acceptance criteria:

```bash
gradle test
```

CI runs the same test suite on Java 17 with Gradle 8.10.2.

## Safety Defaults

- Imported tasks start in `Backlog`.
- Human confirmation is required before cards enter `Ready`.
- Worker completion moves cards to `Review`, never directly to `Done`.
- Dangerous actions create approvals for `shell`, `delete`, `install`, `merge`, and `deploy`.
- Every task has an isolated workspace under `.agentsloop/workspaces/{taskId}`.
