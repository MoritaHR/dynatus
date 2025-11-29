(defproject org.clojars.morita/dynatus "0.1.0-beta"
  :description "A Clojure library for managing DynamoDB table migrations and keeping table definitions in sync between local and production environments"
  :url "https://github.com/MoritaHR/dynatus"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.cognitect.aws/api "0.8.686"]
                 [com.cognitect.aws/endpoints "871.2.39.3"]
                 [com.cognitect.aws/dynamodb "871.2.39.3"]
                 [environ/environ "1.2.0"]]
  
  :source-paths ["src"]
  :resource-paths ["resources"]
  
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "1.4.4"]
                                   [nrepl/nrepl "1.1.0"]
                                   [cider/cider-nrepl "0.45.0"]]
                   :source-paths ["dev"]}
             
             :test {:dependencies [[lambdaisland/kaocha "1.91.1392"]
                                    [org.testcontainers/testcontainers "1.19.3"]
                                    [org.testcontainers/localstack "1.19.3"]
                                    [org.slf4j/slf4j-simple "2.0.9"]]
                    :source-paths ["test"]}}
  
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                     :username :env/clojars_username
                                     :password :env/clojars_password
                                     :sign-releases false}]]
  
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  
  :scm {:name "git"
        :url "https://github.com/MoritaHR/dynatus"}
  
  :pom-addition [:developers [:developer
                               [:name "MoritaHR"]
                               [:url "https://github.com/MoritaHR"]]])