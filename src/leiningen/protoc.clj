(ns leiningen.protoc
  "Leiningen plugin for compiling Google Protocol Buffers"
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.spec :as spec]
            [leiningen.core.main :as main]
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

(defn qualify-path
  [project path]
  (let [p (Paths/get path (into-array String []))]
    (if (.isAbsolute p)
      path
      (-> (Paths/get (str (:root project) File/separator path)
                     (into-array String []))
          .toAbsolutePath
          .toString))))

(defn print-warn-msg
  [e]
  (main/warn (format "Failed to compile proto file(s): %s" e)))

;;
;; Compile Proto
;;

(defn proto?
  [^File file]
  (and (not (.isDirectory file))
       (re-find #".\.proto$" (.getName file))))

(defn java?
  [^File file]
  (and (not (.isDirectory file))
       (re-find #".\.java$" (.getName file))))

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

(defn- outdated-protos?
  [src-paths target-path]
  (let [proto-files (map io/file (mapcat proto-files src-paths))
        out-files (filter java? (file-seq (io/file target-path)))]
    (or (empty? out-files)
        (>= (apply max (map (memfn lastModified) proto-files))
            (apply max (map (memfn lastModified) out-files))))))

(defn resolve-target-path!
  [target-path]
  (let [target-dir (io/file target-path)]
    (if (.exists target-dir)
      target-dir
      (.mkdirs target-dir))
    (.getAbsolutePath target-dir)))

(defn build-cmd
  [{:keys [protoc-exe protoc-grpc-exe]}
   {:keys [proto-source-paths builtin-proto-path]}
   {:keys [proto-target-path grpc-target-path]}]
  (let [all-srcs        (if builtin-proto-path
                          (conj proto-source-paths builtin-proto-path)
                          proto-source-paths)
        src-paths-args  (map str->src-path-arg all-srcs)
        target-path-arg (str "--java_out="
                             (resolve-target-path! proto-target-path))
        grpc-plugin-arg (when protoc-grpc-exe
                          (str "--plugin=" protoc-grpc-exe))
        grpc-path-arg   (when protoc-grpc-exe
                          (str "--grpc-java_out="
                               (resolve-target-path! grpc-target-path)))
        proto-files     (mapcat proto-files proto-source-paths)]
    (when (and (not-empty proto-files)
               (outdated-protos? proto-source-paths proto-target-path))
      (main/info "Compiling" (count proto-files) "proto files:" proto-files)
      (->> (concat [protoc-exe target-path-arg grpc-plugin-arg grpc-path-arg]
                   src-paths-args
                   proto-files)
           (remove nil?)
           vec))))

(defn parse-response
  [process]
  (if (pos? (.exitValue process))
    (print-warn-msg (str \newline (slurp (.getErrorStream process))))
    (main/info "Successfully compiled proto files.")))

(defn compile-proto!
  "Compiles the sources using the provided configurations"
  [compiler-details source-paths target-paths timeout]
  (let [cmd (build-cmd compiler-details source-paths target-paths)]
    (when-let [process (and cmd (.exec (Runtime/getRuntime) (into-array cmd)))]
      (try
        (if (.waitFor process timeout TimeUnit/SECONDS)
          (parse-response process)
          (main/warn "Proto file compilation took more than" timeout "seconds."))
        (catch Exception e
          (print-warn-msg e))
        (finally
          (.destroy process))))))

;;
;; Resolve Proto
;;

(defn latest-version
  [artifact]
  (let [protoc-dep [artifact "(0,]" :extension "pom"]
        repos {"central" {:url (aether/maven-central "central")}}]
    (-> (classpath/dependency-hierarchy
          :deps {:deps [protoc-dep] :repositories repos})
        first
        first
        second)))

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

(defn protoc-grpc-file
  [version classifier]
  (io/file
    (System/getProperty "user.home")
    ".m2"
    "repository"
    "io"
    "grpc"
    "protoc-gen-grpc-java"
    version
    (str "protoc-gen-grpc-java-" version "-" classifier ".exe")))

(defn get-os
  []
  (let [os (leiningen.core.utils/get-os)]
    (name (if (= os :macosx) :osx os))))

(defn get-arch
  []
  (let [arch (leiningen.core.utils/get-arch)]
    (name (if (= arch :x86) :x86_32 arch))))

(defn resolve!
  "Resolves the Google Protocol Buffers code generation artifact+version in the
  local maven repository if it exists or downloads from Maven Central"
  [artifact protoc-version]
  (let [classifier  (str (get-os) "-" (get-arch))
        version     (if (= :latest protoc-version)
                      (latest-version artifact)
                      protoc-version)
        coordinates [artifact version :classifier classifier :extension "exe"]]
    (aether/resolve-artifacts :coordinates [coordinates])
    coordinates))

(defn resolve-protoc!
  "Given a string com.google.protobuf/protoc version or `:latest`, will ensure
  the required protoc executable is available in the local Maven repository
  either from a previous download, or will download from Maven Central."
  [protoc-version]
  (try
    (let [[_ version _ classifier]
          (resolve! 'com.google.protobuf/protoc protoc-version)]
      (let [pfile (protoc-file version classifier)]
        (.setExecutable pfile true)
        (.getAbsolutePath pfile)))
    (catch Exception e
      (print-warn-msg e))))

(defn resolve-protoc-grpc!
  "Given a string io.grpc/protoc-gen-grpc-java version or `:latest`, will
  ensure the required protoc executable is available in the local Maven
  repository either from a previous download, or will download from Maven
  Central."
  [protoc-version]
  (try
    (let [[_ version _ classifier]
          (resolve! 'io.grpc/protoc-gen-grpc-java protoc-version)]
      (let [pfile (protoc-grpc-file version classifier)]
        (.setExecutable pfile true)
        (.getAbsolutePath pfile)))
    (catch Exception e
      (print-warn-msg e))))

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
    (main/info
      (str "The `com.google.protobuf/protobuf-java` dependency is not on "
           "the classpath so any Google standard proto files will not "
           "be available to imports in source protos."))))

;;
;; Options Validation and Parsing
;;

(spec/def :protoc/version
  (spec/nilable (spec/or :string string?
                         :latest #{:latest})))

(spec/def :protoc/source-paths
  (spec/nilable (spec/coll-of string?)))

(spec/def :protoc/target-path
  (spec/nilable string?))

(spec/def :protoc/grpc
  (spec/nilable
    (spec/or :bool #(instance? Boolean %)
             :map  (spec/keys
                     :opt-un [:protoc/version
                              :protoc/target-path]))))

(spec/def :protoc/timeout
  (spec/nilable integer?))

(defn explain
  [spec x]
  (when (not (spec/valid? spec x))
    (spec/explain-str spec x)))

(defn validate
  "Validates the input arguments and returns a vector of error messages"
  [{:keys [protoc-version
           protoc-grpc
           proto-source-paths
           proto-target-path
           protoc-timeout]}]
  (let [v-err (explain :protoc/version protoc-version)
        g-err (explain :protoc/grpc protoc-grpc)
        s-err (explain :protoc/source-paths proto-source-paths)
        t-err (explain :protoc/target-path proto-target-path)
        o-err (explain :protoc/timeout protoc-timeout)]
    (remove nil? [v-err g-err s-err t-err o-err])))

(defn compiler-details
  [{:keys [protoc-version protoc-grpc] :as project}]
  {:protoc-exe
   (resolve-protoc! (or protoc-version +protoc-version-default+))
   :protoc-grpc-exe
   (when protoc-grpc
     (resolve-protoc-grpc!
       (or (:version protoc-grpc) +protoc-version-default+)))})

(defn all-source-paths
  [{:keys [proto-source-paths] :as project}]
  {:proto-source-paths
   (mapv (partial qualify-path project)
         (or proto-source-paths +proto-source-paths-default+))
   :builtin-proto-path
   (resolve-builtin-proto! project)})

(defn all-target-paths
  [{:keys [proto-target-path protoc-grpc] :as project}]
  (let [target-path (or proto-target-path (target-path-default project))]
    {:proto-target-path target-path
     :grpc-target-path  (or (:target-path protoc-grpc) target-path)}))

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

    :protoc-grpc        :: true (or empty map) to generate interfaces for gRPC
                           service definitions with default settings. Can
                           optionally provide a map with the following configs:
                             :version     - version number for gRPC codegen.
                                            Defaults to :latest.
                             :target-path - absolute path or path relative to
                                            the project root where the sources
                                            should be generated. Defaults to
                                            the `:proto-target-path`
                           Defaults to `false`.

    :protoc-timeout     :: timeout value in seconds for the compilation process
                           Defaults to 60
  "
  {:help-arglists '([])}
  [{:keys [protoc-timeout] :as project} & _]
  (let [errors (validate project)]
    (if (not-empty errors)
      (print-warn-msg (format "Invalid configurations received: %s"
                              (string/join "," errors)))
      (compile-proto!
        (compiler-details project)
        (all-source-paths project)
        (all-target-paths project)
        (or protoc-timeout +protoc-timeout-default+)))))

(defn javac-hook
  [f & args]
  (protoc (first args))
  (apply f args))

(defn activate
  []
  (hooke/add-hook #'leiningen.javac/javac #'javac-hook))
