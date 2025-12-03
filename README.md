<div align="center">
  <img src="resources/assets/dynatus_logo.png" alt="Dynatus Logo" width="200"/>
  
  # Dynatus

  [![CI](https://github.com/MoritaHR/dynatus/actions/workflows/ci.yml/badge.svg)](https://github.com/MoritaHR/dynatus/actions/workflows/ci.yml)
  [![Tests](https://github.com/MoritaHR/dynatus/actions/workflows/test.yml/badge.svg)](https://github.com/MoritaHR/dynatus/actions/workflows/test.yml)
  [![Clojars Project](https://img.shields.io/clojars/v/org.clojars.morita/dynatus.svg)](https://clojars.org/org.clojars.morita/dynatus)
  [![Clojars Prereleases](https://img.shields.io/clojars/v/org.clojars.morita/dynatus.svg?include_prereleases)]

  **A Clojure library for managing DynamoDB table migrations and keeping table definitions in sync between local and production environments.**
</div>

---

Highly inspired by migratus and how easy it is to use. Dynatus provides a simple, declarative way to manage DynamoDB table schemas across environments.

## Features

- 📦 EDN-based table definitions
- 🔄 Automatic table creation and synchronization
- 🎯 Idempotent operations - safe to run multiple times
- 🐳 Testcontainers support for integration testing
- 🏠 Local DynamoDB support via interceptors
- ⚡ Simple API with detailed result reporting
- 🔍 Granular control with low-level and high-level APIs

## Installation

Add to your `deps.edn`:

```clojure
;; From Clojars
{:deps {org.clojars.morita/dynatus {:mvn/version "0.1.0-beta"}}}

;; Or from GitHub
{:deps {dynatus/dynatus {:git/url "https://github.com/MoritaHR/dynatus"
                          :git/sha "LATEST_SHA"}}}
```

## Usage

### Quick Start

```clojure
(require '[dynatus.core :as dynatus]
         '[cognitect.aws.client.api :as aws])

;; Create a DynamoDB client using AWS SDK
(def dynamo-client (aws/client {:api :dynamodb
                                :region "us-east-1"}))

;; Synchronize tables from EDN files in resources/dynamo
(dynatus/syncronizate {:client dynamo-client
                       :path "resources/dynamo"})
;; => {:sync true
;;     :count 2
;;     :migrated [{:table "users" :action :create}
;;                {:table "orders" :action :create}]}
```

### API Overview

Dynatus provides two main functions for managing DynamoDB tables:

#### `syncronizate` - High-level synchronization

Loads table definitions from EDN files and synchronizes them with DynamoDB:

```clojure
(dynatus/syncronizate {:client dynamo-client
                       :path "resources/dynamo"}) ; optional, defaults to "./resources/dynamo"
```

**Returns:**
- On success: `{:sync true :count n :migrated [{:table "name" :action :create/:noop/:recreate}...]}`
- On empty directory: `{:sync false :reason "No DynamoDB migration files found." :path "..."}`
- On error: `{:sync false :reason "Exception..." :error "error message"}`

#### `execute-tables-sync` - Low-level table operations

Directly synchronizes a collection of table definitions:

```clojure
(def table-defs
  [{:TableName "users"
    :KeySchema [{:AttributeName "user_id" :KeyType "HASH"}]
    :AttributeDefinitions [{:AttributeName "user_id" :AttributeType "S"}]
    :BillingMode "PAY_PER_REQUEST"}])

(dynatus/execute-tables-sync dynamo-client table-defs)
;; => [{:table "users" :action :create}]
```

**Returns:** Vector of `{:table "name" :action :create/:noop/:recreate}` for each table processed.

### Table Definition Format

Create `.edn` files in your migrations directory:

```clojure
;; resources/dynamo/users.edn
{:TableName "users"
 :KeySchema [{:AttributeName "user_id"
              :KeyType "HASH"}]
 :AttributeDefinitions [{:AttributeName "user_id"
                         :AttributeType "S"}
                        {:AttributeName "email"
                         :AttributeType "S"}]
 :BillingMode "PAY_PER_REQUEST"
 :GlobalSecondaryIndexes [{:IndexName "email-index"
                           :KeySchema [{:AttributeName "email"
                                        :KeyType "HASH"}]
                           :Projection {:ProjectionType "ALL"}}]
 :StreamSpecification {:StreamEnabled true
                       :StreamViewType "NEW_AND_OLD_IMAGES"}
 :TimeToLiveSpecification {:Enabled true
                           :AttributeName "ttl"}
 :Tags [{:Key "Environment"
         :Value "production"}]}
```

### Local Development

For local development, configure your AWS client to connect to local DynamoDB:

```clojure
(require '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.interceptors :as interceptors])

;; Define interceptor for local DynamoDB
(defmethod interceptors/modify-http-request "dynamodb"
  [service http-request]
  (-> http-request
      (assoc :scheme :http
             :server-port 8000
             :server-name "localhost")
      (assoc-in [:headers "host"] "localhost:8000")))

;; Create client - interceptor will redirect to local
(def local-client (aws/client {:api :dynamodb
                               :region "us-east-1"}))

;; Synchronize tables
(dynatus/syncronizate {:client local-client})
```

### Testing with Testcontainers

```clojure
(require '[dynatus.test-fixtures :as fixtures])

(use-fixtures :each fixtures/with-dynamodb-container)

(deftest my-test
  (testing "DynamoDB operations"
    ;; Use fixtures/*test-client* which is automatically connected
    ;; to a DynamoDB container
    (let [client fixtures/*test-client*
          result (dynatus/syncronizate {:client client
                                        :path "test/resources/tables"})]
      (is (= true (:sync result)))
      ;; Your test assertions here
      )))
```

### Time-to-Live (TTL) Support

Dynatus supports automatic configuration of DynamoDB Time-to-Live settings. Simply include a `TimeToLiveSpecification` in your table definition:

```clojure
;; resources/dynamo/sessions.edn
{:TableName "sessions"
 :KeySchema [{:AttributeName "session_id"
              :KeyType "HASH"}]
 :AttributeDefinitions [{:AttributeName "session_id"
                         :AttributeType "S"}]
 :BillingMode "PAY_PER_REQUEST"
 :TimeToLiveSpecification {:Enabled true
                           :AttributeName "expiry"}}
```

The TTL will be automatically configured after the table is created and becomes active. The `expiry` attribute should contain a Unix timestamp (seconds since epoch) indicating when the item should expire.

**Note:** The TTL attribute (`expiry` in this example) should NOT be included in `AttributeDefinitions` unless it's also used as a key attribute or in an index. DynamoDB only requires attributes to be defined if they're part of the key schema or indexes.

## Understanding the Synchronization Process

### Actions

Dynatus performs three types of actions on tables:

1. **`:create`** - Table doesn't exist and will be created
2. **`:noop`** - Table exists and matches the definition (no operation needed)
3. **`:recreate`** - Table exists but key schema has changed (requires recreation)

### Idempotency

All operations are idempotent - running `syncronizate` multiple times is safe:

```clojure
;; First run - creates tables
(dynatus/syncronizate {:client client})
;; => {:sync true :count 2 :migrated [{:table "users" :action :create}...]}

;; Second run - no changes needed
(dynatus/syncronizate {:client client})
;; => {:sync true :count 2 :migrated [{:table "users" :action :noop}...]}
```

### Error Handling

The library provides detailed error information:

```clojure
;; Empty directory
(dynatus/syncronizate {:client client :path "empty/dir"})
;; => {:sync false 
;;     :reason "No DynamoDB migration files found."
;;     :path "empty/dir"}

;; Connection error
(dynatus/syncronizate {:client bad-client})
;; => {:sync false
;;     :reason "Exception while executing DynamoDB sync."
;;     :error "Connection refused"}
```

## Running Tests

```bash
# Run all tests
clojure -M:test -m kaocha.runner

# Run with specific test
clojure -M:test -m kaocha.runner --focus dynatus.core-test

# Run tests in watch mode
clojure -M:test -m kaocha.runner --watch
```

## Project Structure

```
dynatus/
├── deps.edn                 # Dependencies
├── project.clj              # Leiningen configuration
├── src/
│   └── dynatus/
│       ├── core.clj        # Main API (syncronizate, execute-tables-sync)
│       ├── loader.clj      # Table definition loader
│       ├── diff.clj        # Table comparison logic
│       └── apply.clj       # Apply migrations
├── test/
│   └── dynatus/
│       ├── core_test.clj      # Integration tests
│       ├── test_fixtures.clj  # Testcontainers setup
│       └── test_client.clj    # Test-specific client with interceptors
└── resources/
    └── dynamo/             # Table definitions
        ├── users.edn
        └── orders.edn
```

## API Reference

### Core Functions

#### `syncronizate`
```clojure
(syncronizate {:client aws-client
               :path "path/to/edn/files"}) ; optional
```
High-level function that loads EDN files from a directory and synchronizes tables with DynamoDB.

**Parameters:**
- `:client` - AWS DynamoDB client (required)
- `:path` - Path to directory containing EDN files (optional, defaults to "./resources/dynamo")

**Returns:**
- Success: `{:sync true :count n :migrated [...]}`
- Failure: `{:sync false :reason "..." :error "..." :path "..."}`

#### `execute-tables-sync`
```clojure
(execute-tables-sync client table-definitions)
```
Low-level function that synchronizes a collection of table definitions.

**Parameters:**
- `client` - AWS DynamoDB client
- `table-definitions` - Collection of table definition maps

**Returns:**
- Vector of `{:table "name" :action :create/:noop/:recreate}`

#### `wait-for-table-active`
```clojure
(wait-for-table-active client table-name)
```
Waits for a table to become active after creation.

**Parameters:**
- `client` - AWS DynamoDB client
- `table-name` - Name of the table to wait for

**Returns:**
- `true` when table is active
- `nil` if timeout occurs

## Environment Variables

- `IN_DOCKER` - Set to "true" when running in Docker
- `DYNAMODB_LOCAL_ENDPOINT` - Override local DynamoDB endpoint
- `AWS_PROFILE` - AWS profile for credentials

## Building and Deployment

### Build JAR

```bash
# Build the JAR file (using Leiningen)
lein jar

# Or using deps.edn
clojure -X:jar

# This creates target/dynatus.jar
```

### Install Locally

```bash
# Install to local Maven repository (~/.m2) using Leiningen
lein install

# Or using deps.edn
clojure -X:install
```

### Deploy to Clojars

Deployment is handled through GitHub Actions when you create a new release tag.

#### Automated Deployment (Recommended)

1. Set up GitHub secrets:
   - `CLOJARS_USERNAME`: Your Clojars username
   - `CLOJARS_TOKEN`: Your Clojars deploy token

2. Create and push a version tag:
   ```bash
   git tag v0.1.0-beta
   git push origin v0.1.0-beta
   ```

3. GitHub Actions will automatically deploy to Clojars

#### Manual Deployment

If you need to deploy manually:

```bash
# Set environment variables
export CLOJARS_USERNAME=your-username
export CLOJARS_PASSWORD=your-deploy-token

# Deploy using Leiningen
lein deploy clojars
```

### Using as a Dependency

Once deployed to Clojars:

```clojure
;; deps.edn
{:deps {org.clojars.morita/dynatus {:mvn/version "0.1.0-beta"}}}

;; Leiningen project.clj
[org.clojars.morita/dynatus "0.1.0-beta"]
```

## CI/CD

### Continuous Integration

The project uses GitHub Actions for automated testing and deployment:

#### Test Workflows

**`ci.yml`** - Lightweight CI for every push/PR
- Runs tests on Java 11 and 17
- Caches dependencies for faster builds
- Provides quick feedback on code changes

**`test.yml`** - Comprehensive test suite
- Tests against Java 11, 17, and 21
- Runs linting with clj-kondo
- Checks code formatting with cljfmt
- Uploads test artifacts for debugging

#### Deployment Workflow

**`deploy_clojars.yml`** - Automated deployment
- Triggers on version tags (e.g., `v0.1.0-beta`)
- Uses Leiningen for building and deployment
- Automatically updates version in project.clj if needed
- Creates GitHub releases with installation instructions
- Requires `CLOJARS_USERNAME` and `CLOJARS_TOKEN` secrets

### Running Tests Locally

```bash
# Run all tests
make test

# Run tests with specific Java version
JAVA_HOME=/path/to/java11 make test

# Run with verbose output
clojure -M:test -m kaocha.runner --reporter documentation

# Run specific test namespace
clojure -M:test -m kaocha.runner --focus dynatus.core-test
```

### Making a Release

#### Automated Release (Recommended)

1. Commit your changes: `git commit -am "Prepare release v0.1.0-beta"`
2. Tag the release: `git tag v0.1.0-beta`
3. Push with tags: `git push origin main --tags`
4. GitHub Actions will automatically:
   - Build the JAR using Leiningen
   - Deploy to Clojars with proper credentials
   - Create a GitHub release with installation instructions

#### Manual Release

You can also trigger a deployment manually from GitHub Actions:
1. Go to Actions → Deploy to Clojars
2. Click "Run workflow"
3. Enter the version number (e.g., `0.1.0-beta`)
4. Click "Run workflow"

### Setting up GitHub Secrets

For automated deployment, configure these secrets in your GitHub repository:

1. Go to Settings → Secrets and variables → Actions
2. Add the following secrets:
   - `CLOJARS_USERNAME`: Your Clojars username
   - `CLOJARS_TOKEN`: Your Clojars deploy token (get from [Clojars → Deploy Tokens](https://clojars.org/tokens))

## Migration from Previous Versions

If you were using the previous `migrate` function, update your code:

```clojure
;; Old API
(dynatus/migrate {:client client :path "resources/dynamo"})

;; New API
(dynatus/syncronizate {:client client :path "resources/dynamo"})
;; Returns more detailed information about the synchronization
```

The new API provides:
- Better error handling with detailed failure reasons
- Explicit success/failure status
- Detailed migration results for each table
- Support for default paths

## License

Copyright © 2025 MoritaHR

Distributed under the MIT License.