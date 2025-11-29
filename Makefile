.PHONY: test clean build install deploy help

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

test: ## Run all tests with testcontainers
	clojure -M:test -m kaocha.runner

test-watch: ## Run tests in watch mode
	clojure -M:test -m kaocha.runner --watch

repl: ## Start a REPL with dev dependencies
	clojure -M:dev:test

clean: ## Clean build artifacts
	rm -rf target/
	rm -rf .cpcache/

build: clean ## Build the JAR file
	clojure -X:jar

install: build ## Install JAR to local Maven repository
	clojure -X:install

deploy: build ## Deploy to Clojars
	@echo "Deploying to Clojars..."
	@echo "Make sure you have configured ~/.m2/settings.xml with Clojars credentials"
	clojure -X:deploy

snapshot: clean ## Deploy snapshot version
	clojure -X:jar :version '"0.1.0-SNAPSHOT"'
	clojure -X:deploy :version '"0.1.0-SNAPSHOT"'

lint: ## Run clj-kondo linter
	clj-kondo --lint src test

format: ## Format code with cljfmt
	clojure -M:dev -m cljfmt.main fix src test

outdated: ## Check for outdated dependencies
	clojure -M:outdated

docker-test: ## Run tests in Docker with DynamoDB
	docker-compose -f test/docker-compose.yml up --abort-on-container-exit --exit-code-from tests