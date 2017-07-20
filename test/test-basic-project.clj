(defproject proto-test "0.1.0"
  :description "Test for lein-protoc Plugin (basic)"
  :proto-source-paths ["proto/basic"]
  :plugins [[lein-protoc "0.1.0"]]
  :dependencies [[com.google.protobuf/protobuf-java "3.3.1"]
                 [org.clojure/clojure "1.9.0-alpha15"]])
