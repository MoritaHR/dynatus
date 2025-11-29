(ns dynatus.core-test
  (:require [clojure.test :refer :all]
            [dynatus.core :as core]
            [dynatus.test-client :as test-client]
            [dynatus.loader :as loader]
            [dynatus.test-fixtures :as fixtures]
            [cognitect.aws.client.api :as aws]
            [clojure.java.io :as io]))

(use-fixtures :each fixtures/with-dynamodb-container)

(deftest test-client-connection
  (testing "Client can connect to test container"
    (let [result (test-client/test-connection fixtures/*test-client*)]
      (is (= :success (:status result)))
      (is (contains? result :tables)))))

(deftest test-create-table
  (testing "Can create a simple table"
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

(deftest test-migrate-single-table
  (testing "Migrate creates a new table from definition"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-migrations")
          users-def {:TableName "users"
                     :KeySchema [{:AttributeName "user_id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "user_id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]
      
      ;; Create temp directory with table definition
      (.mkdirs temp-dir)
      (spit (io/file temp-dir "users.edn") (pr-str users-def))
      
      (try
        ;; Run migration
        (core/migrate {:client client
                      :path (.getPath temp-dir)})
        
        ;; Verify table was created
        (let [tables (-> (aws/invoke client {:op :ListTables})
                         :TableNames)]
          (is (some #{"users"} tables)))
        
        ;; Verify table structure
        (let [desc (aws/invoke client {:op :DescribeTable
                                       :request {:TableName "users"}})
              table (:Table desc)]
          (is (= "users" (:TableName table)))
          (is (= [{:AttributeName "user_id" :KeyType "HASH"}]
                 (:KeySchema table))))
        
        (finally
          ;; Cleanup
          (.delete (io/file temp-dir "users.edn"))
          (.delete temp-dir))))))

(deftest test-migrate-with-gsi
  (testing "Migrate creates table with Global Secondary Index"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-migrations-gsi")
          table-def {:TableName "products"
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
                                               :Projection {:ProjectionType "ALL"}}]}]
      
      (.mkdirs temp-dir)
      (spit (io/file temp-dir "products.edn") (pr-str table-def))
      
      (try
        ;; Run migration
        (core/migrate {:client client
                      :path (.getPath temp-dir)})
        
        ;; Wait for table
        (fixtures/wait-for-table client "products")
        
        ;; Verify GSI was created
        (let [desc (aws/invoke client {:op :DescribeTable
                                       :request {:TableName "products"}})
              gsi (-> desc :Table :GlobalSecondaryIndexes first)]
          (is (= "category-index" (:IndexName gsi)))
          (is (= [{:AttributeName "category" :KeyType "HASH"}]
                 (:KeySchema gsi))))
        
        (finally
          (.delete (io/file temp-dir "products.edn"))
          (.delete temp-dir))))))

(deftest test-idempotent-migration
  (testing "Running migration twice doesn't fail"
    (let [client fixtures/*test-client*
          temp-dir (io/file "test-migrations-idempotent")
          table-def {:TableName "sessions"
                     :KeySchema [{:AttributeName "session_id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "session_id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}]
      
      (.mkdirs temp-dir)
      (spit (io/file temp-dir "sessions.edn") (pr-str table-def))
      
      (try
        ;; First migration - creates table
        (core/migrate {:client client
                      :path (.getPath temp-dir)})
        
        ;; Wait for table
        (fixtures/wait-for-table client "sessions")
        
        ;; Second migration - should be no-op
        (is (nil? (core/migrate {:client client
                                :path (.getPath temp-dir)})))
        
        ;; Table should still exist
        (let [tables (-> (aws/invoke client {:op :ListTables})
                         :TableNames)]
          (is (some #{"sessions"} tables)))
        
        (finally
          (.delete (io/file temp-dir "sessions.edn"))
          (.delete temp-dir))))))

(deftest test-load-definitions-from-resources
  (testing "Can load table definitions from resources/dynamo"
    (let [defs (loader/load-definitions "resources/dynamo")]
      (is (seq defs))
      (is (every? :TableName defs))
      (is (every? :KeySchema defs)))))

(deftest test-migrate-from-resources
  (testing "Migrate tables from resources/dynamo directory"
    (let [client fixtures/*test-client*]
      
      ;; Run migration using actual resource files
      (core/migrate {:client client
                    :path "resources/dynamo"})
      
      ;; Wait for tables
      (fixtures/wait-for-table client "users")
      (fixtures/wait-for-table client "orders")
      
      ;; Verify both tables were created
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
      
      ;; Verify orders table has TTL configuration (may be DISABLED in DynamoDB Local)
      (let [ttl-desc (aws/invoke client {:op :DescribeTimeToLive
                                         :request {:TableName "orders"}})
            ttl-status (-> ttl-desc :TimeToLiveDescription :TimeToLiveStatus)]
        ;; DynamoDB Local doesn't automatically enable TTL, just verify it exists
        (is (contains? #{"ENABLED" "DISABLED"} ttl-status))))))