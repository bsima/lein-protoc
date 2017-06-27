(ns leiningen.protoc-test
  (:require [leiningen.protoc :as lp]
            [leiningen.core.project :as lcp]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(def project
  (lcp/read "test/test-project.clj"))

(def proto-file (io/file "test/proto/Foo.proto"))

(def java-file (io/file "test/target/generated-sources/protobuf/com/liaison/foo/FooProtos.java"))

(t/deftest protoc-test
  (.delete java-file)
  (let [result (lp/protoc project)
        first-timestamp (.lastModified java-file)]
    (t/is (.exists proto-file))
    (t/is (.exists java-file))
    ;; lastModified time only has resolution down to the second, not ms
    (Thread/sleep 1000)
    (lp/protoc project)
    (let [second-timestamp (.lastModified java-file)]
      (t/is (.exists java-file))
      (t/is (= first-timestamp second-timestamp))
      (Thread/sleep 1000)
      (.setLastModified proto-file (System/currentTimeMillis))
      (lp/protoc project)
      (t/is (.exists java-file))
      (t/is (not= first-timestamp (.lastModified java-file))))))
