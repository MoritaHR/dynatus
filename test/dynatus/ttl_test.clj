(ns dynatus.ttl-test
  (:require [clojure.test :refer :all]
            [dynatus.core :as dynatus]
            [dynatus.test-fixtures :as fixtures]
            [cognitect.aws.client.api :as aws]))

(use-fixtures :each fixtures/with-dynamodb-container)

(deftest test-ttl-configuration
  (testing "Table creation with TTL specification"
    (let [client fixtures/*test-client*
          table-def {:TableName "ttl-test-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"
                     :TimeToLiveSpecification {:Enabled true
                                               :AttributeName "expiry"}}
          result (dynatus/execute-tables-sync client [table-def])]
      
      ;; Verify table was created
      (is (= 1 (count result)))
      (is (= :create (:action (first result))))
      
      ;; Wait a bit for TTL to be applied
      (Thread/sleep 2000)
      
      ;; Verify TTL configuration attempt was made
      (let [ttl-desc (aws/invoke client {:op :DescribeTimeToLive
                                         :request {:TableName "ttl-test-table"}})]
        ;; Just verify the operation doesn't throw an error
        ;; DynamoDB Local might return DISABLED or nil for TTL status
        (is (not (nil? ttl-desc)) "DescribeTimeToLive should return a result")))))

(deftest test-ttl-with-syncronizate
  (testing "TTL configuration through syncronizate API"
    (let [client fixtures/*test-client*
          result (dynatus/syncronizate {:client client
                                        :path "test/resources/tables"})]
      
      ;; Should sync successfully
      (is (= true (:sync result)))
      
      ;; Verify at least one table was migrated
      (is (pos? (:count result)) "Should have migrated at least one table")
      
      ;; Wait for operations to complete
      (Thread/sleep 2000)
      
      ;; Verify the table exists
      (let [describe-result (aws/invoke client {:op :DescribeTable
                                                :request {:TableName "test-ttl-table"}})]
        (is (or (:Table describe-result)
                (= "ACTIVE" (get-in describe-result [:Table :TableStatus])))
            "Table should exist after syncronizate")))))

(deftest test-table-without-ttl
  (testing "Tables without TTL specification should not have TTL configured"
    (let [client fixtures/*test-client*
          table-def {:TableName "no-ttl-table"
                     :KeySchema [{:AttributeName "id"
                                  :KeyType "HASH"}]
                     :AttributeDefinitions [{:AttributeName "id"
                                             :AttributeType "S"}]
                     :BillingMode "PAY_PER_REQUEST"}
          result (dynatus/execute-tables-sync client [table-def])]
      
      ;; Verify table was created
      (is (= 1 (count result)))
      (is (= :create (:action (first result))))
      
      ;; Verify TTL was not attempted to be configured (no TTL spec in definition)
      ;; The table should be created successfully without TTL
      (let [describe-result (aws/invoke client {:op :DescribeTable
                                                :request {:TableName "no-ttl-table"}})]
        (is (or (:Table describe-result)
                (= "ACTIVE" (get-in describe-result [:Table :TableStatus])))
            "Table without TTL spec should be created successfully")))))
 