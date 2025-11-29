(ns dynamigrate.test-client
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.interceptors :as interceptors]
            [environ.core :refer [env]]))

(defmethod interceptors/modify-http-request "dynamodb"
  [service endpoint http-request]
  (let [local-endpoint (env :dynamodb-local-endpoint)]
    (println "Interceptor called. Local endpoint:" local-endpoint)
    (println "Original request:" (select-keys http-request [:scheme :server-name :server-port]))
    ;; Always redirect to local endpoint if it's set
    (if local-endpoint
      (let [[_ host port-str] (re-matches #"https?://([^:]+):(\d+)" local-endpoint)
            port (Integer/parseInt (or port-str "8000"))
            modified-request (-> http-request
                                (assoc :scheme :http
                                       :server-port port
                                       :server-name host)
                                (assoc-in [:headers "host"] (str host ":" port)))]
        (println "Modified request:" (select-keys modified-request [:scheme :server-name :server-port]))
        modified-request)
      http-request)))

(defn create-client
  "Create a DynamoDB client with optional configuration.
   Options:
   - :region - AWS region (default: us-east-1)
   - :profile - AWS profile name for credentials
   - :endpoint - Local endpoint URL (e.g., http://localhost:8000)
   - :credentials-provider - Custom credentials provider"
  [& [{:keys [region endpoint]
       :or {region "us-east-1"}}]]
  (when endpoint
    (alter-var-root #'env assoc :dynamodb-local-endpoint endpoint))
  (aws/client {:api :dynamodb
               :region region
               ;; Use properly formatted dummy credentials for local testing
               ;; DynamoDB Local validates format but not actual values
               :credentials-provider (credentials/basic-credentials-provider
                                     {:access-key-id "AKIAIOSFODNN7EXAMPLE"
                                      :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})}))

 

(defn test-connection
  "Test the DynamoDB connection by listing tables"
  [client]
  (try
    (let [result (aws/invoke client {:op :ListTables})]
      (if (:cognitect.anomalies/category result)
        {:status :error
         :message "Failed to connect to DynamoDB"
         :error result}
        {:status :success
         :message "Successfully connected to DynamoDB"
         :tables (:TableNames result [])}))
    (catch Exception e
      {:status :error
       :message "Exception while connecting to DynamoDB"
       :error (.getMessage e)})))
 
