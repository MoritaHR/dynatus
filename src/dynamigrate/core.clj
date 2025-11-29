(ns dynamigrate.core
  (:require [dynamigrate.loader :as loader]
            [dynamigrate.diff :as diff]
            [dynamigrate.apply :as apply]
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

(defn migrate
  "Run migrations for DynamoDB.
   Options:
   {:client <aws-client>
    :path   \"resources/dynamo\"}"
  [{:keys [client path] :as opts}]
  (let [defs (loader/load-definitions path)]
    (doseq [definition defs]
      (let [table-name (:TableName definition)
            actual     (loader/describe-table client table-name)
            d          (diff/compute definition actual)]
        (apply/apply-diff client d)
        ;; Always wait after any action that creates/modifies a table
        (when (#{:create :recreate} (:action d))
          (wait-for-table-active client table-name))))))