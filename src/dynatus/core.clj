(ns dynatus.core
  (:require [dynatus.loader :as loader]
            [dynatus.diff :as diff]
            [dynatus.apply :as apply]
            [cognitect.aws.client.api :as aws]))

(defn wait-for-table-active
  "Wait for a table to become active after creation"
  [client table-name]
  (println "Waiting for table" table-name "to become active...")
  (loop [attempts 0]
    (when (< attempts 30) ; Max 30 attempts (30 seconds)
      (Thread/sleep 1000) ; Wait first to give table time to be created
      (let [result (aws/invoke client {:op :DescribeTable
                                       :request {:TableName table-name}})
            status (get-in result [:Table :TableStatus])]
        (cond
          (= status "ACTIVE")
          (do (println "  ✓ Table" table-name "is active")
              true)
          
          ;; Check if table exists but is in another state
          (contains? #{"CREATING" "UPDATING"} status)
          (do (println "  Table" table-name "status:" status "- waiting...")
              (recur (inc attempts)))
          
          ;; Handle anomalies (table doesn't exist yet)
          (:cognitect.anomalies/category result)
          (do (println "  Table" table-name "not ready yet, waiting..."
                       "Anomaly:" (:cognitect.anomalies/category result))
              (when (= attempts 0)
                (println "  First attempt anomaly details:" result))
              (recur (inc attempts)))
          
          ;; DynamoDB Local might return the table immediately as ACTIVE
          ;; Check if we have a Table in the result
          (:Table result)
          (do (println "  ✓ Table" table-name "is ready (DynamoDB Local)")
              true)
          
          :else
          (do (println "  Waiting for table" table-name "... Result keys:" (keys result))
              (recur (inc attempts))))))))

(defn update-time-to-live
  "Update Time-to-Live specification for a table"
  [client table-name attribute-name]
  (println "  Setting TTL on" table-name "with attribute" attribute-name)
  (try
    (let [result (aws/invoke client
                            {:op :UpdateTimeToLive
                             :request {:TableName table-name
                                      :TimeToLiveSpecification {:Enabled true
                                                               :AttributeName attribute-name}}})]
      (cond
        ;; Success case
        (get-in result [:TimeToLiveSpecification :TimeToLiveStatus])
        (println "  ✓ TTL configured for" table-name)
        
        ;; Check for anomalies
        (:cognitect.anomalies/category result)
        (if (= (:cognitect.anomalies/category result) :cognitect.anomalies/incorrect)
          ;; DynamoDB Local might not support TTL - this is not a fatal error
          (println "  ⚠ TTL not supported (likely DynamoDB Local)")
          (println "  ⚠ Failed to set TTL:" (:cognitect.anomalies/message result)))
        
        ;; Other success indicators
        :else
        (println "  ✓ TTL update initiated for" table-name)))
    (catch Exception e
      (println "  ⚠ Exception setting TTL:" (.getMessage e)))))

(defn apply-ttl-if-specified
  "Apply TTL configuration if specified in the table definition"
  [client definition]
  (when-let [ttl-spec (:TimeToLiveSpecification definition)]
    (when (:Enabled ttl-spec)
      (update-time-to-live client
                          (:TableName definition)
                          (:AttributeName ttl-spec)))))

(defn execute-tables-sync
  [client defs]
  (mapv
   (fn [definition]
     (let [table-name (:TableName definition)
           actual     (loader/describe-table client table-name)
           d          (diff/compute definition actual)]
       (apply/apply-diff client d)
       (when (#{:create :recreate} (:action d))
         (wait-for-table-active client table-name)
         ;; Apply TTL after table is active
         (apply-ttl-if-specified client definition))
       ;; Return the diff so the caller knows what happened
       {:table table-name
        :action (:action d)}))
   defs))

(defn syncronizate
  "Run migrations for DynamoDB.
   Options:
   {:client <aws-client>
    :path   \"resources/dynamo\"}"
  [{:keys [client path]}]
  (let [path (or path "./resources/dynamo")
        defs (loader/load-definitions path)]
    (try
      (if (empty? defs)
        {:sync false
         :reason "No DynamoDB migration files found."
         :path path}

        (let [results (execute-tables-sync client defs)]
          {:sync true
           :migrated results
           :count (count results)}))

      (catch Exception e
        {:sync false
         :reason "Exception while executing DynamoDB sync."
         :error (.getMessage e)}))))