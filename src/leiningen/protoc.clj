(ns leiningen.protoc
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.spec :as spec]
            [leiningen.core.main]
            [leiningen.core.utils]
            [leiningen.javac]
            [robert.hooke :as hooke]
            [clojure.string :as string])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]
           [org.sonatype.aether.util.artifact DefaultArtifact]
           [org.sonatype.aether.resolution VersionRangeRequest]
           [org.sonatype.aether RepositorySystem]))

(def +proto-source-paths+
  ["src/proto"])

(def +protoc-timeout+
  60)

(defn print-exception-msg
  [e]
  (leiningen.core.main/warn
    (format "Failed to compile proto file(s): %s"
            (if (instance? Exception e)
              (.getMessage e)
              e))))

;;
;; Compile Proto
;;

(defn proto?
  [^File file]
  (let [split (-> file .getName (string/split #"\."))
        ext   (last split)]
    (and (not (.isDirectory file))
         (> (count split) 1)
         (= ext "proto"))))

(defn proto-files
  [source-directory]
  (->> source-directory
       io/file
       file-seq
       (filter proto?)
       (map #(.getAbsolutePath %))))

(defn str->src-path-arg
  [p]
  (str "-I=" (.getAbsolutePath (io/file p))))

(defn build-cmd
  [protoc-cmd src-paths target-path]
  (let [src-paths-args  (map str->src-path-arg src-paths)
        target-path-arg (str "--java_out=" target-path)
        proto-files     (into [] (mapcat proto-files src-paths))]
    (leiningen.core.main/info
      (format "Compiling %s proto files: %s" (count proto-files) proto-files))
    (->> (vector protoc-cmd src-paths-args target-path-arg proto-files)
         flatten
         vec
         into-array)))

(defn compile-proto
  "Given the fully qualified path to the protoc executable, a vector of
  relative or qualified source paths for the proto files, a relative or
  qualified target path for the generated sources, and a timeout value for
  the compilation, will run the Google Protocol Buffers Compiler and output
  the generated Java sources."
  [protoc-path src-paths target-path timeout]
  (try
    (let [cmd     (build-cmd protoc-path src-paths target-path)
          process (.exec (Runtime/getRuntime) cmd)]
      (try
        (if (.waitFor process timeout TimeUnit/SECONDS)
          (leiningen.core.main/info "Successfully compiled proto files")
          (print-exception-msg
            (format "Proto file compilation timed out after %s seconds"
                    timeout)))
        (catch Exception e
          (print-exception-msg e))
        (finally
          (.destroy process))))
    (catch Exception e
      (print-exception-msg e))))

;;
;; Resolve Protoc
;;

(defn latest-protoc-version
  []
  (let [system   (#'cemerick.pomegranate.aether/repository-system)
        session  (aether/repository-session
                   {:repository-system system
                    :offline? false})
        repo     (#'cemerick.pomegranate.aether/make-repository
                   (first aether/maven-central)
                   nil)
        artifact (DefaultArtifact. "com.google.protobuf:protoc:(0,]")
        request  (VersionRangeRequest. artifact, [repo], nil)
        result   (.resolveVersionRange system session request)]
    (.toString (.getHighestVersion result))))

(defn protoc-file
  [version classifier]
  (io/file
    (System/getProperty "user.home")
    ".m2"
    "repository"
    "com"
    "google"
    "protobuf"
    "protoc"
    version
    (str "protoc-" version "-" classifier ".exe")))

(defn get-os
  []
  (let [os (leiningen.core.utils/get-os)]
    (name (if (= os :macosx) :osx os))))

(defn get-arch
  []
  (let [arch (leiningen.core.utils/get-arch)]
    (name (if (= arch :x86) :x86_32 arch))))

(defn resolve-protoc
  "Given a string com.google.protobuf/protoc version or `:latest`, will ensure
  the required protoc executable is available in the local Maven repository
  either from a previous download, or will download from Maven Central."
  [protoc-version]
  (let [classifier (str (get-os) "-" (get-arch))
        version    (if (= :latest protoc-version)
                     (latest-protoc-version)
                     protoc-version)]
    (try
      (aether/resolve-artifacts
        :coordinates [['com.google.protobuf/protoc
                       version
                       :classifier classifier
                       :extension "exe"]])
      (let [pfile (protoc-file version classifier)]
        (.setExecutable pfile true)
        (.getAbsolutePath pfile))
      (catch Exception e
        (print-exception-msg e)))))

;;
;; Validate
;;

(spec/def :protoc/version
  (spec/nilable (spec/or :string string?
                         :latest #{:latest})))

(spec/def :protoc/source-paths
  (spec/nilable (spec/coll-of string?)))

(spec/def :protoc/target-path
  (spec/nilable string?))

(spec/def :protoc/timeout
  (spec/nilable integer?))

(defn explain
  [spec x]
  (when (not (spec/valid? spec x))
    (spec/explain-str spec x)))

(defn validate
  "Validates the input arguments and returns a vector of error messages"
  [{:keys [protoc-version
           proto-source-paths
           proto-target-path
           protoc-timeout]}]
  (let [v-err (explain :protoc/version protoc-version)
        s-err (explain :protoc/source-paths proto-source-paths)
        t-err (explain :protoc/target-path proto-target-path)
        o-err (explain :protoc/timeout protoc-timeout)]
    (remove nil? [v-err s-err t-err o-err])))

;;
;; Main
;;

(defn protoc
  "Compiles Google Protocol Buffer proto files to Java Sources.

  The following options are available and should be configured in the
  project.clj:

    :protoc-version     :: the Protocol Buffers Compiler version to use.
                           Defaults to `:latest`
    :proto-source-paths :: vector of absolute paths or paths relative to
                           the project root that contain the .proto files
                           to be compiled. Defaults to `[\"src/proto\"]`
    :proto-target-path  :: the absolute path or path relative to the project
                           root where the sources should be generated. Defaults
                           to the `:target-path`
    :protoc-timeout     :: timeout value in seconds for the compilation process
                           Defaults to 60
  "
  {:help-arglists '([])}
  [{:keys [protoc-version
           proto-source-paths
           proto-target-path
           protoc-timeout] :as project} & _]
  (let [errors (validate project)]
    (if (not-empty errors)
      (print-exception-msg (format "Invalid configurations received: %s"
                                   (string/join "," errors)))
      (compile-proto
        (resolve-protoc (or protoc-version :latest))
        (or proto-source-paths +proto-source-paths+)
        (or proto-target-path (:target-path project))
        (or protoc-timeout +protoc-timeout+)))))

(defn javac-hook
  [f & args]
  (protoc (first args))
  (apply f args))

(defn activate
  []
  (hooke/add-hook #'leiningen.javac/javac #'javac-hook))
