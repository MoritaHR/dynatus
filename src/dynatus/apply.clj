(ns dynatus.apply
  (:require [cognitect.aws.client.api :as aws]))

(defn apply-diff [client {:keys [action definition]}]
  (case action
    :noop
    (println "✓ Table" (:TableName definition) "is up to date.")

    :create
    (do
      (println "➕ Creating table" (:TableName definition))
      (let [result (aws/invoke client {:op :CreateTable
                                       :request definition})]
        (if (:cognitect.anomalies/category result)
          (println "  ⚠ Failed to create table:"
                   (:cognitect.anomalies/category result)
                   (:cognitect.anomalies/message result))
          (println "  ✓ Table creation initiated"))
        result))

    :recreate
    (do
      (println "🔁 Recreating table" (:TableName definition))
      (aws/invoke client {:op :DeleteTable
                          :request {:TableName (:TableName definition)}})
      (Thread/sleep 3000)
      (aws/invoke client {:op :CreateTable
                          :request definition}))

    (println "Unknown action:" action)))