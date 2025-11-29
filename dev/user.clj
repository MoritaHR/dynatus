(ns user
  (:require [dynamigrate.test-fixtures :as fixtures]
            [cognitect.aws.client.api :as aws]))
 
(defn start-test-env
  "Start a test environment with DynamoDB container"
  []
  (let [container (fixtures/start-dynamodb-container)
        client (fixtures/create-test-client container)]
    {:container container
     :client client
     :endpoint (fixtures/get-container-endpoint container)}))

(defn stop-test-env
  "Stop the test environment"
  [{:keys [container]}]
  (.stop container))
 
(defn list-all-operations
  "List all available DynamoDB operations"
  [client]
  (-> client
      aws/ops
      keys
      sort))
 