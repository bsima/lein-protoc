(ns leiningen.protoc
  "Leiningen plugin for compiling Google Protocol Buffers"
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.spec :as spec]
            [leiningen.core.main]
            [leiningen.core.utils]
            [leiningen.core.classpath :as classpath]
            [leiningen.javac]
            [robert.hooke :as hooke]
            [clojure.string :as string])
  (:import [java.io File]
           [java.net URI]
           [java.nio.file
            CopyOption
            Files
            FileSystems
            FileVisitResult
            LinkOption
            Path
            Paths
            SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def +protoc-version-default+
  :latest)

(def +proto-source-paths-default+
  ["src/proto"])

(def +protoc-timeout-default+
  60)

(defn target-path-default
  [project]
  (str (:target-path project)
       "/generated-sources/protobuf"))

(defn print-warn-msg
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

(defn str->src-path-arg
  [p]
  (str "-I=" (.getAbsolutePath (io/file p))))

(defn proto-files
  [source-directory]
  (->> source-directory
       io/file
       file-seq
       (filter proto?)
       (map #(.getAbsolutePath %))))

(defn build-cmd
  [protoc-path src-paths target-path builtin-proto-path]
  (let [src-paths-args  (map str->src-path-arg (conj src-paths builtin-proto-path))
        target-path-arg (str "--java_out=" target-path)
        proto-files     (into [] (mapcat proto-files src-paths))]
    (leiningen.core.main/info
      (format "Compiling %s proto files: %s" (count proto-files) proto-files))
    (->> (vector protoc-path src-paths-args target-path-arg proto-files)
         flatten
         vec
         into-array)))

(defn resolve-target-path!
  [target-path]
  (let [target-dir (io/file target-path)]
    (if (.exists target-dir)
      target-dir
      (doto target-dir .mkdirs))))

(defn parse-response
  [process]
  (if (> (.exitValue process) 0)
    (print-warn-msg (str \newline (slurp (.getErrorStream process))))
    (leiningen.core.main/info "Successfully compiled proto files")))

(defn compile-proto!
  "Given the fully qualified path to the protoc executable, a vector of
  relative or qualified source paths for the proto files, a relative or
  qualified target path for the generated sources, and a timeout value for
  the compilation, will run the Google Protocol Buffers Compiler and output
  the generated Java sources."
  [protoc-path src-paths target-path timeout builtin-proto-path]
  (try
    (let [target-path (-> target-path resolve-target-path! .getAbsolutePath)
          cmd         (build-cmd protoc-path
                                 src-paths
                                 target-path
                                 builtin-proto-path)
          process     (.exec (Runtime/getRuntime) cmd)]
      (try
        (if (.waitFor process timeout TimeUnit/SECONDS)
          (parse-response process)
          (print-warn-msg
            (format "Proto file compilation timed out after %s seconds"
                    timeout)))
        (catch Exception e
          (print-warn-msg e))
        (finally
          (.destroy process))))
    (catch Exception e
      (print-warn-msg e))))

;;
;; Resolve Proto
;;

(defn latest-protoc-version []
  (let [protoc-dep ['com.google.protobuf/protoc "(0,]" :extension "pom"]
        repos {"central" {:url (aether/maven-central "central")}}]
    (-> (classpath/dependency-hierarchy :deps {:deps [protoc-dep]
                                               :repositories repos})
        first first second)))

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

(defn resolve-protoc!
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
        (print-warn-msg e)))))

(defn vargs
  [t]
  (into-array t nil))

(defn resolve-mismatched
  [target-path source-path new-path]
  (.resolve
    target-path
    (-> source-path
        (.relativize new-path)
        .toString
        (Paths/get (vargs String)))))

(defn jar-uri
  [jar-string]
  (URI. "jar:file" (-> (File. jar-string) .toURI .getPath) nil))

(defn unpack-jar!
  [proto-jar]
  (with-open [proto-jar-fs (FileSystems/newFileSystem
                             ^URI (jar-uri proto-jar)
                             {})]
    (let [src-path (.getPath proto-jar-fs "/" (vargs String))
          tgt-path (Files/createTempDirectory
                     "lein-protoc"
                     (vargs FileAttribute))
          tgt-file (.toFile tgt-path)]
      (.deleteOnExit tgt-file)
      (Files/walkFileTree
        src-path
        (proxy [SimpleFileVisitor] []
          (preVisitDirectory [dir-path attrs]
            (when (.startsWith dir-path "/google")
              (let [target-dir (resolve-mismatched tgt-path src-path dir-path)]
                (when (Files/notExists target-dir (vargs LinkOption))
                  (Files/createDirectories target-dir (vargs FileAttribute))
                  (-> target-dir .toFile .deleteOnExit))))
            FileVisitResult/CONTINUE)
          (visitFile [file attrs]
            (when (.startsWith file "/google")
              (let [tgt-file-path (resolve-mismatched tgt-path src-path file)]
                (Files/copy file tgt-file-path (vargs CopyOption))
                (-> tgt-file-path .toFile .deleteOnExit)))
            FileVisitResult/CONTINUE)))
      tgt-file)))

(def proto-jar-regex
  ".*com[\\/|\\\\]google[\\/|\\\\]protobuf[\\/|\\\\]protobuf-java[\\/|\\\\].*")

(defn resolve-builtin-proto!
  "If the project.clj includes the `com.google.protobuf/protobuf-java`
  dependency, then we unpack it to a temporary location to use during
  compilation in order to make the builtin proto files available."
  [project]
  (if-let [proto-jar (->> project
                          leiningen.core.classpath/get-classpath
                          (filter #(.matches % proto-jar-regex))
                          first)]
    (-> proto-jar unpack-jar! .getAbsolutePath)
    (print-warn-msg 
      (str "The `com.google.protobuf/protobuf-java` dependency is not on "
           "the classpath so any Google standard proto files will not "
           "be available to imports in source protos."))))

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
                           to `${target-path}/generated-sources/protobuf`
    :protoc-timeout     :: timeout value in seconds for the compilation process
                           Defaults to 60
  "
  {:help-arglists '([])}
  [{:keys [protoc-version
           proto-source-paths
           proto-target-path
           protoc-timeout
           proto-include-builtin?] :as project} & _]
  (let [errors (validate project)]
    (if (not-empty errors)
      (print-warn-msg (format "Invalid configurations received: %s"
                              (string/join "," errors)))
      (compile-proto!
        (resolve-protoc! (or protoc-version +protoc-version-default+))
        (or proto-source-paths +proto-source-paths-default+)
        (or proto-target-path (target-path-default project))
        (or protoc-timeout +protoc-timeout-default+)
        (resolve-builtin-proto! project)))))

(defn javac-hook
  [f & args]
  (protoc (first args))
  (apply f args))

(defn activate
  []
  (hooke/add-hook #'leiningen.javac/javac #'javac-hook))
