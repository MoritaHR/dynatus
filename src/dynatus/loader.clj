(ns dynatus.loader
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cognitect.aws.client.api :as aws]))

(defn load-definitions [path]
  (->> (file-seq (io/file path))
       (filter #(-> % .getName (.endsWith ".edn")))
       (mapv (fn [f]
               (edn/read-string (slurp f))))))

(defn describe-table [client table-name]
  (let [res (aws/invoke client {:op :DescribeTable
                                :request {:TableName table-name}})]
    (when-not (:cognitect.anomalies/category res)
      res)))