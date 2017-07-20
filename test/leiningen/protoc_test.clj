(ns leiningen.protoc-test
  (:require [leiningen.protoc :as lp]
            [leiningen.core.project :as lcp]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(def basic-project
  (lcp/read "test/test-basic-project.clj"))

(def basic-proto-file
  (io/file "test/proto/basic/Foo.proto"))

(def basic-java-file
  (io/file "test/target/generated-sources/protobuf/com/liaison/foo/FooProtos.java"))

(def grpc-project
  (lcp/read "test/test-grpc-project.clj"))

(def grpc-java-file-1
  (io/file "test/target/generated-sources/protobuf/com/liaison/bar/BarProtos.java"))

(def grpc-java-file-2
  (io/file "test/target/generated-sources/protobuf/com/liaison/bar/BarServiceGrpc.java"))

(t/use-fixtures
  :each
  (fn [f]
    (.delete basic-java-file)
    (.delete grpc-java-file-1)
    (.delete grpc-java-file-2)
    (f)))

(t/deftest basic-protoc-test
  (t/testing "Basic proto compilation"
    (let [result (lp/protoc basic-project)
          first-timestamp (.lastModified basic-java-file)]
      (t/is (.exists basic-proto-file))
      (t/is (.exists basic-java-file))
      ;; lastModified time only has resolution down to the second, not ms
      (Thread/sleep 1000)
      (lp/protoc basic-project)
      (let [second-timestamp (.lastModified basic-java-file)]
        (t/is (.exists basic-java-file))
        (t/is (= first-timestamp second-timestamp))
        (Thread/sleep 1000)
        (.setLastModified basic-proto-file (System/currentTimeMillis))
        (lp/protoc basic-project)
        (t/is (.exists basic-java-file))
        (t/is (not= first-timestamp (.lastModified basic-java-file)))))))

(t/deftest grpc-protoc-test
  (t/testing "gRPC proto compilation"
    (lp/protoc grpc-project)
    (t/is (.exists grpc-java-file-1))
    (t/is (.exists grpc-java-file-2))))
