# Servora — local development orchestration.
# Run `make help` for an overview of available targets.

SHELL := /bin/bash
COMPOSE := docker compose
API_DIR := api
GRADLEW := gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this overview of available targets
	@awk -F ':.*?## ' '/^[a-zA-Z0-9._%-]+:.*?## / { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

# ---------------------------------------------------------------- foundation

.PHONY: up down down-v restart logs ps
up: ## Build and start the full foundation (PostgreSQL + API + MinIO) in the background
	$(COMPOSE) up -d --build

down: ## Stop and remove foundation containers and networks (database data is kept)
	$(COMPOSE) down

down-v: ## Stop foundation AND delete the PostgreSQL and MinIO data volumes (destructive)
	$(COMPOSE) down -v

restart: ## Restart all foundation services
	$(COMPOSE) restart

logs: ## Follow logs of all foundation services
	$(COMPOSE) logs -f --tail=200

ps: ## Show foundation service status
	$(COMPOSE) ps

# ----------------------------------------------------------------- database

.PHONY: db-shell migrate migration seed
db-shell: ## Open a psql shell against the local development PostgreSQL
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-servora} -d $${POSTGRES_DB:-servora}

migrate: api-build ## Apply pending database migrations using the API tooling on the host
	cd $(API_DIR) && npm run db:migrate

NAME ?= migration
migration: api-build ## Generate a new migration, e.g. `make migration NAME=add_technician_profile`
	cd $(API_DIR) && npm run db:generate -- --name $(NAME)

seed: api-build ## Seed the local database: the two QA accounts and the demo dataset (replaces it)
	cd $(API_DIR) && npm run db:seed

# ------------------------------------------------------------ object storage

.PHONY: minio-console minio-shell minio-ls
minio-console: ## Print the local object-storage Console URL and how to sign in
	@echo "MinIO Console: http://localhost:$${MINIO_CONSOLE_PORT:-9001}"
	@echo "Credentials:   MINIO_ROOT_USER / MINIO_ROOT_PASSWORD from the repository-root .env"
	@echo "Bucket:        S3_BUCKET from the repository-root .env (default: servora-dev)"

minio-shell: ## Open a shell inside the MinIO container with an authenticated `mc` alias
	$(COMPOSE) exec minio /bin/sh -c 'mc alias set local http://127.0.0.1:9000 "$$MINIO_ROOT_USER" "$$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; exec /bin/sh'

# The image ships an unauthenticated `local` alias, so every operator command sets its own alias.
# The credentials and the bucket come from the service's own environment, which is the same source
# `minio` and `minio-init` use, so they cannot drift from the running stack.
minio-ls: ## List the objects in the local Servora bucket
	$(COMPOSE) run --rm --no-deps minio-init 'mc alias set servora http://minio:9000 "$$MINIO_ROOT_USER" "$$MINIO_ROOT_PASSWORD" >/dev/null && mc ls "servora/$$S3_BUCKET"'

# ---------------------------------------------------------------------- API

.PHONY: api-install api-build api-test api-test-e2e api-lint api-format api-start api-start-dev
api-install: ## Install API dependencies (npm ci)
	cd $(API_DIR) && npm ci

api-build: ## Build the API (nest build)
	cd $(API_DIR) && npm run build

api-test: ## Run the API unit tests (Vitest)
	cd $(API_DIR) && npm test

api-test-e2e: ## Run the API e2e tests against PostgreSQL (requires `make up`)
	cd $(API_DIR) && npm run test:e2e

api-lint: ## Lint the API (oxlint)
	cd $(API_DIR) && npm run lint

api-format: ## Format the API sources (prettier)
	cd $(API_DIR) && npm run format

api-start: ## Run the compiled API on the host (requires PostgreSQL reachable on localhost:5432)
	cd $(API_DIR) && npm run start:prod

api-start-dev: ## Run the API in watch mode on the host
	cd $(API_DIR) && npm run start:dev

# ------------------------------------------------------------------ Android

.PHONY: android-build android-test android-lint
android-build: ## Assemble the Android debug APK
	cd android && ./$(GRADLEW) assembleDebug

android-test: ## Run the Android JVM unit tests
	cd android && ./$(GRADLEW) testDebugUnitTest

android-lint: ## Run Android lint on the debug variant
	cd android && ./$(GRADLEW) lintDebug

# -------------------------------------------------------------------- hygiene

# Android verification (and any Gradle build) leaves two heavyweight daemons behind: the Gradle
# daemon (~5 GB resident) and the Kotlin daemon (~2.4 GB). Both are built to outlive the command
# that started them — they idle for hours — so a few Android targets in a row quietly consume the
# machine. `android-stop` releases them; `tidy` is the end-of-task check
# (`dev.md` §18, `qa.md` §7.4, `docs/development/setup.md` §8).

.PHONY: android-stop tidy
android-stop: ## Stop the Gradle/Kotlin build daemons the Android targets leave running
	cd android && ./$(GRADLEW) --stop

tidy: android-stop ## Stop the build daemons, then report anything else a task may have left running
	@echo "Java build daemons:"; \
	  found=$$(pgrep -af 'Gradle[D]aemon|Kotlin[C]ompileDaemon' || true); \
	  if [ -n "$$found" ]; then \
	    echo "$$found" | sed 's/^/  /'; \
	    echo "  NOTE: a daemon is still running — a build may be in progress"; \
	  else echo "  none"; fi
	@echo "Node/API processes:"; \
	  found=$$(pgrep -af 'nest [s]tart|start:[d]ev|vites[t]|playwrigh[t]' || true); \
	  if [ -n "$$found" ]; then echo "$$found" | sed 's/^/  /'; else echo "  none"; fi
	@echo "Foundation containers:"; \
	  states=$$($(COMPOSE) ps --format '  {{.Service}}: {{.State}}' 2>/dev/null || true); \
	  if [ -n "$$states" ]; then echo "$$states"; else echo "  none running"; fi

# ------------------------------------------------------------------- aliases

.PHONY: test lint build
test: ## Alias for api-test (repository-wide test entry point; Android uses android-test)
	$(MAKE) api-test

lint: ## Alias for api-lint
	$(MAKE) api-lint

build: ## Alias for api-build
	$(MAKE) api-build

# Note: there is intentionally no `e2e-web` target yet — the Angular client
# does not exist in this foundation milestone (see docs/tracker/001-foundation.md).
