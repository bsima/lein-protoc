(defproject proto-test "0.1.0"
  :description "Test for lein-protoc Plugin (gRPC)"
  :proto-source-paths ["proto/grpc"]
  :protoc-grpc true
  :plugins [[lein-protoc "0.1.0"]]
  :dependencies [[com.google.protobuf/protobuf-java "3.3.1"]
                 [io.grpc/grpc-netty "1.5.0"]
                 [io.grpc/grpc-protobuf "1.5.0"]
                 [io.grpc/grpc-stub "1.5.0"]
                 [org.clojure/clojure "1.9.0-alpha15"]])
