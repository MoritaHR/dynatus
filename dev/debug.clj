(ns debug
  (:require [dynamigrate.test-fixtures :as fixtures]
            [dynamigrate.test-client :as test-client]
            [clojure.java.shell :as shell]))


(defn check-docker []
  (println "Checking Docker...")
  (let [result (shell/sh "docker" "ps")]
    (println (:out result))))

(defn test-direct-connection
  "Test connection directly without interceptor"
  [endpoint]
  (println "Testing direct connection to" endpoint)
  (try
    (let [url (java.net.URL. endpoint)
          conn (.openConnection url)]
      (.setConnectTimeout conn 1000)
      (.connect conn)
      (println "✓ Direct connection successful!")
      true)
    (catch Exception e
      (println "✗ Direct connection failed:" (.getMessage e))
      false)))

(defn debug-container []
  (println "\n=== Starting Debug Session ===\n") 
  (println "1. Starting DynamoDB container...")
  (let [container (fixtures/start-dynamodb-container)]
    (Thread/sleep 2000)

    (let [endpoint (fixtures/get-container-endpoint container)]
      (println "2. Container endpoint:" endpoint)

      (println "3. Container running?" (.isRunning container))

      (println "4. Testing direct HTTP connection...")
      (test-direct-connection endpoint)

      (println "5. Container logs:")
      (println (.getLogs container))

      (println "6. Creating DynamoDB client...")
      (let [client (test-client/create-client {:endpoint endpoint})]
        (println "7. Testing DynamoDB connection...")
        (let [result (test-client/test-connection client)]
          (println "Result:" result))

        {:container container
         :endpoint endpoint
         :client client}))))


(defn cleanup [env]
  (when (:container env)
    (println "Stopping container...")
    (.stop (:container env))
    (println "Container stopped.")))
 