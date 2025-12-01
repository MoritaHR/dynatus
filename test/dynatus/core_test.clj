(ns dynatus.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dynatus.core :as core]
            [dynatus.test-client :as test-client]
            [dynatus.loader :as loader]
            [dynatus.test-fixtures :as fixtures]
            [cognitect.aws.client.api :as aws]
            [clojure.java.io :as io]))

(use-fixtures :each fixtures/with-dynamodb-container)

;; ========================================
;; Basic Connection Tests
;; ========================================

(deftest test-client-connection
  (testing "Client can connect to test container"
    (let [result (test-client/test-connection fixtures/*test-client*)]
      (is (= :success (:status result)))
      (is (contains? result :tables)))))

(deftest test-create-table-directly
  (testing "Can create a simple table using AWS SDK directly"
    (let [client fixtures/*test-client*
          table-def {:TableName "test-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]

      ;; Create table
      (let [result (aws/invoke client {:op :CreateTable
                                       :request table-def})]
        (is (not (:cognitect.anomalies/category result))))

      ;; Wait for table to be active
      (fixtures/wait-for-table client "test-table")

      ;; Verify table exists
      (let [tables (-> (aws/invoke client {:op :ListTables})
                       :TableNames)]
        (is (some #{"test-table"} tables))))))

;; ========================================
;; execute-tables-sync Tests
;; ========================================

(deftest test-execute-tables-sync-creates-new-tables
  (testing "execute-tables-sync creates new tables and returns action results"
    (let [client fixtures/*test-client*
          defs [{:TableName "products"
                 :KeySchema [{:AttributeName "product_id"
                              :KeyType "HASH"}]
                 :AttributeDefinitions [{:AttributeName "product_id"
                                         :AttributeType "S"}]
                 :BillingMode "PAY_PER_REQUEST"}
                {:TableName "categories"
                 :KeySchema [{:AttributeName "category_id"
                              :KeyType "HASH"}]
                 :AttributeDefinitions [{:AttributeName "category_id"
                                         :AttributeType "S"}]
                 :BillingMode "PAY_PER_REQUEST"}]
          results (core/execute-tables-sync client defs)
          product-result (first results)
          category-result (second results)]
        ;; Verify return structure
      (is (vector? results))
      (is (= 2 (count results)))

        ;; Check each result
      (is (= "products" (:table product-result)))
      (is (= :create (:action product-result)))
      (is (= "categories" (:table category-result)))
      (is (= :create (:action category-result)))

        ;; Verify tables were actually created
      (fixtures/wait-for-table client "products")
      (fixtures/wait-for-table client "categories")

      (let [tables (-> (aws/invoke client {:op :ListTables})
                       :TableNames
                       set)]
        (is (contains? tables "products"))
        (is (contains? tables "categories"))))))

(deftest test-execute-tables-sync-idempotency
  (testing "execute-tables-sync returns noop for existing tables"
    (let [client fixtures/*test-client*
          defs [{:TableName "idempotent-test"
                 :KeySchema [{:AttributeName "id"
                              :KeyType "HASH"}]
                 :AttributeDefinitions [{:AttributeName "id"
                                         :AttributeType "S"}]
                 :BillingMode "PAY_PER_REQUEST"}]]

      ;; First execution - creates table
      (let [results1 (core/execute-tables-sync client defs)]
        (is (= :create (-> results1 first :action))))

      ;; Wait for table
      (fixtures/wait-for-table client "idempotent-test")

      ;; Second execution - should be noop
      (let [results2 (core/execute-tables-sync client defs)]
        (is (= :noop (-> results2 first :action)))
        (is (= "idempotent-test" (-> results2 first :table)))))))

(deftest test-execute-tables-sync-with-gsi
  (testing "execute-tables-sync handles tables with Global Secondary Indexes"
    (let [client fixtures/*test-client*
          defs [{:TableName "products-with-gsi"
                 :KeySchema [{:AttributeName "product_id"
                              :KeyType "HASH"}]
                 :AttributeDefinitions [{:AttributeName "product_id"
                                         :AttributeType "S"}
                                        {:AttributeName "category"
                                         :AttributeType "S"}]
                 :BillingMode "PAY_PER_REQUEST"
                 :GlobalSecondaryIndexes [{:IndexName "category-index"
                                           :KeySchema [{:AttributeName "category"
                                                        :KeyType "HASH"}]
                                           :Projection {:ProjectionType "ALL"}}]}]]

      ;; Execute sync
      (let [results (core/execute-tables-sync client defs)]
        (is (= :create (-> results first :action))))

      ;; Wait and verify GSI
      (fixtures/wait-for-table client "products-with-gsi")

      (let [desc (aws/invoke client {:op :DescribeTable
                                     :request {:TableName "products-with-gsi"}})
            gsi (-> desc :Table :GlobalSecondaryIndexes first)]
        (is (= "category-index" (:IndexName gsi)))
        (is (= [{:AttributeName "category" :KeyType "HASH"}]
               (:KeySchema gsi)))))))

;; ========================================
;; syncronizate Tests
;; ========================================

(deftest test-syncronizate-creates-tables-from-directory
  (testing "syncronizate loads EDN files and creates tables"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-sync-dir")
          users-def {:TableName "sync-users"
                     :KeySchema [{:AttributeName "user_id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "user_id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}
          orders-def {:TableName "sync-orders"
                      :KeySchema [{:AttributeName "order_id"
                                   :KeyType "HASH"}]
                      :AttributeDefinitions [{:AttributeName "order_id"
                                              :AttributeType "S"}]
                      :BillingMode "PAY_PER_REQUEST"}]

      ;; Create temp directory with table definitions
      (.mkdirs temp-dir)
      (spit (io/file temp-dir "users.edn") (pr-str users-def))
      (spit (io/file temp-dir "orders.edn") (pr-str orders-def))

      (try
        ;; Run syncronizate
        (let [result (core/syncronizate {:client client
                                         :path (.getPath temp-dir)})]
          ;; Verify success response
          (is (= true (:sync result)))
          (is (= 2 (:count result)))
          (is (= 2 (count (:migrated result))))

          ;; Check individual migration results
          (let [migrated-tables (set (map :table (:migrated result)))
                migrated-actions (set (map :action (:migrated result)))]
            (is (contains? migrated-tables "sync-users"))
            (is (contains? migrated-tables "sync-orders"))
            (is (= #{:create} migrated-actions))))

        ;; Verify tables were created
        (fixtures/wait-for-table client "sync-users")
        (fixtures/wait-for-table client "sync-orders")

        (let [tables (-> (aws/invoke client {:op :ListTables})
                         :TableNames
                         set)]
          (is (contains? tables "sync-users"))
          (is (contains? tables "sync-orders")))

        (finally
          ;; Cleanup
          (.delete (io/file temp-dir "users.edn"))
          (.delete (io/file temp-dir "orders.edn"))
          (.delete temp-dir))))))

(deftest test-syncronizate-handles-empty-directory
  (testing "syncronizate returns appropriate response for empty directory"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-empty-sync")]

      (.mkdirs temp-dir)

      (try
        (let [result (core/syncronizate {:client client
                                         :path (.getPath temp-dir)})]
          (is (= false (:sync result)))
          (is (= "No DynamoDB migration files found." (:reason result)))
          (is (= (.getPath temp-dir) (:path result))))

        (finally
          (.delete temp-dir))))))

(deftest test-syncronizate-uses-default-path
  (testing "syncronizate uses ./resources/dynamo as default path"
    (let [client fixtures/*test-client*
          ;; Call without path parameter
          result (core/syncronizate {:client client})]
      (is (map? result))
      (is (contains? result :sync))
      ;; If resources/dynamo exists and has files, it should sync
      ;; Otherwise it should return false with reason
      (if (:sync result)
        (do
          (is (number? (:count result)))
          (is (vector? (:migrated result))))
        (is (string? (:reason result)))))))

(deftest test-syncronizate-idempotency
  (testing "syncronizate can be run multiple times safely"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-idempotent-sync")
          table-def {:TableName "idempotent-sync-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]

      (.mkdirs temp-dir)
      (spit (io/file temp-dir "table.edn") (pr-str table-def))

      (try
        ;; First sync - creates table
        (let [result1 (core/syncronizate {:client client
                                          :path (.getPath temp-dir)})]
          (is (= true (:sync result1)))
          (is (= 1 (:count result1)))
          (is (= :create (-> result1 :migrated first :action))))

        ;; Wait for table
        (fixtures/wait-for-table client "idempotent-sync-table")

        ;; Second sync - should be noop
        (let [result2 (core/syncronizate {:client client
                                          :path (.getPath temp-dir)})]
          (is (= true (:sync result2)))
          (is (= 1 (:count result2)))
          (is (= :noop (-> result2 :migrated first :action))))

        (finally
          (.delete (io/file temp-dir "table.edn"))
          (.delete temp-dir))))))

(deftest test-syncronizate-handles-timeout
  (testing "syncronizate handles table creation timeout gracefully"
    ;; This test simulates a timeout scenario by using a bad client
    ;; that will cause wait-for-table-active to timeout
    (let [bad-client (test-client/create-client {:endpoint "http://invalid:9999"})
          temp-dir (io/file "test-timeout-dir")
          table-def {:TableName "timeout-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]

      (.mkdirs temp-dir)
      (spit (io/file temp-dir "table.edn") (pr-str table-def))

      (try
        ;; This should complete successfully even though wait-for-table-active times out
        ;; because wait-for-table-active returns nil on timeout but doesn't throw
        (let [result (core/syncronizate {:client bad-client
                                         :path (.getPath temp-dir)})]
          ;; The sync will succeed because the table creation request itself doesn't fail
          ;; Only the wait-for-table-active will timeout (return nil)
          (is (= true (:sync result)))
          (is (= 1 (:count result))))

        (finally
          (.delete (io/file temp-dir "table.edn"))
          (.delete temp-dir))))))

;; ========================================
;; Integration Tests with Resource Files
;; ========================================

(deftest test-load-definitions-from-resources
  (testing "Can load table definitions from resources/dynamo"
    (let [defs (loader/load-definitions "resources/dynamo")]
      (is (seq defs))
      (is (every? :TableName defs))
      (is (every? :KeySchema defs))
      (is (every? :AttributeDefinitions defs)))))

(deftest test-syncronizate-with-resource-files
  (testing "syncronizate works with actual resource files"
    (let [client fixtures/*test-client*]

      ;; Run syncronizate using actual resource files
      (let [result (core/syncronizate {:client client
                                       :path "resources/dynamo"})]
        (is (= true (:sync result)))
        (is (= 2 (:count result))) ; users and orders tables

        ;; Verify migration results
        (let [migrated-map (reduce (fn [acc m]
                                     (assoc acc (:table m) (:action m)))
                                   {}
                                   (:migrated result))]
          (is (contains? migrated-map "users"))
          (is (contains? migrated-map "orders"))))

      ;; Wait for tables
      (fixtures/wait-for-table client "users")
      (fixtures/wait-for-table client "orders")

      ;; Verify tables were created with correct structure
      (let [tables (-> (aws/invoke client {:op :ListTables})
                       :TableNames
                       set)]
        (is (contains? tables "users"))
        (is (contains? tables "orders")))

      ;; Verify users table has GSI
      (let [desc (aws/invoke client {:op :DescribeTable
                                     :request {:TableName "users"}})
            gsi (-> desc :Table :GlobalSecondaryIndexes first)]
        (is (= "email-index" (:IndexName gsi))))

      ;; Verify orders table has multiple GSIs
      (let [desc (aws/invoke client {:op :DescribeTable
                                     :request {:TableName "orders"}})
            gsis (-> desc :Table :GlobalSecondaryIndexes)]
        (is (= 2 (count gsis)))
        (let [gsi-names (set (map :IndexName gsis))]
          (is (contains? gsi-names "user-orders-index"))
          (is (contains? gsi-names "status-index")))))))

;; ========================================
;; Edge Cases and Complex Scenarios
;; ========================================

(deftest test-execute-tables-sync-with-composite-keys
  (testing "execute-tables-sync handles tables with composite keys"
    (let [client fixtures/*test-client*
          defs [{:TableName "composite-key-table"
                 :KeySchema [{:AttributeName "partition_key"
                              :KeyType "HASH"}
                             {:AttributeName "sort_key"
                              :KeyType "RANGE"}]
                 :AttributeDefinitions [{:AttributeName "partition_key"
                                         :AttributeType "S"}
                                        {:AttributeName "sort_key"
                                         :AttributeType "N"}]
                 :BillingMode "PAY_PER_REQUEST"}]]

      (let [results (core/execute-tables-sync client defs)]
        (is (= :create (-> results first :action))))

      (fixtures/wait-for-table client "composite-key-table")

      (let [desc (aws/invoke client {:op :DescribeTable
                                     :request {:TableName "composite-key-table"}})
            key-schema (-> desc :Table :KeySchema)]
        (is (= 2 (count key-schema)))
        (is (some #(and (= "partition_key" (:AttributeName %))
                        (= "HASH" (:KeyType %))) key-schema))
        (is (some #(and (= "sort_key" (:AttributeName %))
                        (= "RANGE" (:KeyType %))) key-schema))))))

(deftest test-wait-for-table-active
  (testing "wait-for-table-active properly waits for table creation"
    (let [client fixtures/*test-client*
          table-def {:TableName "wait-test-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]

      ;; Create table
      (aws/invoke client {:op :CreateTable :request table-def})

      ;; Use the wait function
      (let [result (core/wait-for-table-active client "wait-test-table")]
        (is (= true result)))

      ;; Verify table is active
      (let [desc (aws/invoke client {:op :DescribeTable
                                     :request {:TableName "wait-test-table"}})
            status (get-in desc [:Table :TableStatus])]
        (is (= "ACTIVE" status))))))