(ns dynatus.test-fixtures
  (:require [dynatus.test-client :as test-client]
            [cognitect.aws.client.api :as aws])
  (:import [org.testcontainers.containers GenericContainer]
           [org.testcontainers.utility DockerImageName]))

(def ^:dynamic *test-client* nil)
(def ^:dynamic *test-container* nil)

(defn start-dynamodb-container
  "Start a DynamoDB Local container for testing"
  []
  (let [container (GenericContainer.
                   (DockerImageName/parse "amazon/dynamodb-local:latest"))]
    (.addExposedPort container (int 8000))
    ;; Use only valid DynamoDB Local options
    (.withCommand container ^"[Ljava.lang.String;"
                  (into-array String ["-jar" "DynamoDBLocal.jar"
                                     "-inMemory"
                                     "-sharedDb"]))
    (.start container)
    container))

(defn get-container-endpoint
  "Get the endpoint URL for the running container"
  [container]
  (format "http://%s:%d" 
          (.getHost container)
          (.getMappedPort container 8000)))

(defn create-test-client
  "Create a DynamoDB client connected to the test container"
  [container]
  (let [endpoint (get-container-endpoint container)]
    (println "Creating client for endpoint:" endpoint)
    (test-client/create-client {:endpoint endpoint
                                :region "us-east-1"})))

(defn with-dynamodb-container
  "Test fixture that starts DynamoDB container and provides a client"
  [test-fn]
  (let [container (start-dynamodb-container)
        client (create-test-client container)]
    (binding [*test-container* container
              *test-client* client]
      (try
        (test-fn)
        (finally
          (.stop container))))))

(defn clean-tables
  "Delete all tables in the test DynamoDB instance"
  [client]
  (let [tables (-> (aws/invoke client {:op :ListTables})
                   :TableNames)]
    (doseq [table-name tables]
      (aws/invoke client {:op :DeleteTable
                         :request {:TableName table-name}})
      ;; Wait for deletion
      (Thread/sleep 100))))

(defn wait-for-table
  "Wait for a table to become active"
  [client table-name & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [result (aws/invoke client {:op :DescribeTable
                                      :request {:TableName table-name}})
            status (get-in result [:Table :TableStatus])]
        (println "Table" table-name "status:" status "Result keys:" (keys result))
        (cond
          (= status "ACTIVE") true
          (:cognitect.anomalies/category result)
          (throw (ex-info "Error describing table"
                          {:table table-name :error result}))
          (> (- (System/currentTimeMillis) start) timeout-ms)
          (throw (ex-info "Timeout waiting for table"
                          {:table table-name :status status}))
          :else (do (Thread/sleep 100)
                   (recur)))))))

(defn create-test-table
  "Helper to create a simple test table"
  [client table-name & {:keys [partition-key sort-key] 
                        :or {partition-key "id"}}]
  (let [key-schema (cond-> [{:AttributeName partition-key
                             :KeyType "HASH"}]
                     sort-key (conj {:AttributeName sort-key
                                    :KeyType "RANGE"}))
        attr-defs (cond-> [{:AttributeName partition-key
                           :AttributeType "S"}]
                    sort-key (conj {:AttributeName sort-key
                                   :AttributeType "S"}))]
    (aws/invoke client {:op :CreateTable
                       :request {:TableName table-name
                                :KeySchema key-schema
                                :AttributeDefinitions attr-defs
                                :BillingMode "PAY_PER_REQUEST"}})
    (wait-for-table client table-name)))