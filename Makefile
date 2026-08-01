.PHONY: build test typecheck verify local local-down local-contract

build:
	./mvnw --batch-mode --no-transfer-progress -DskipTests package
	npm run build

test:
	./mvnw --batch-mode --no-transfer-progress test
	npm test

typecheck:
	npm run typecheck

verify:
	./mvnw --batch-mode --no-transfer-progress verify
	npm test
	npm run test:browser
	npm run typecheck
	npm run build
	./scripts/verify-local-environment.sh

local:
	docker compose --profile workers up --build

local-down:
	docker compose --profile workers down

local-contract:
	./scripts/verify-local-environment.sh
