(ns leiningen.protoc-test
  (:require [leiningen.protoc :as lp]
            [leiningen.core.project :as lcp]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(def project
  (lcp/read "test/test-project.clj"))

(t/deftest protoc-test
  (let [result (lp/protoc project)]
    (t/is (.exists (io/file "test/proto/Foo.proto")))
    (t/is (.exists (io/file "test/target/generated-sources/protobuf/com/liaison/foo/FooProtos.java")))))
