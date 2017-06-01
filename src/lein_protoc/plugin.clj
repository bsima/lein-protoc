(ns lein-protoc.plugin
  (:require [leiningen.protoc :as protoc]))

(defn hooks []
  (protoc/activate))
