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
    (when (< attempts 5) ; Max 5 attempts (5 seconds)
      (Thread/sleep 1000) ; Wait first to give table time to be created
      (let [result (aws/invoke client {:op :DescribeTable
                                       :request {:TableName table-name}})
            status (get-in result [:Table :TableStatus])]
        (println "  Table" table-name "status:" status)
        (cond
          (= status "ACTIVE")
          (do (println "  ✓ Table" table-name "is active")
              true)
          (:cognitect.anomalies/category result)
          (do (println "  Table" table-name "not found yet, waiting...")
              (recur (inc attempts)))
          :else
          (recur (inc attempts)))))))

(defn execute-tables-sync
  [client defs]
  (mapv
   (fn [definition]
     (let [table-name (:TableName definition)
           actual     (loader/describe-table client table-name)
           d          (diff/compute definition actual)]
       (apply/apply-diff client d)
       (when (#{:create :recreate} (:action d))
         (wait-for-table-active client table-name))
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