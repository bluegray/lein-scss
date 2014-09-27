(ns leiningen.scss
  "Compile a scss project to css using any sass convertion tool."
  (:require [juxt.dirwatch :refer [watch-dir close-watcher]]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as lein]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:dynamic *boring* false)

(def ansi
  {:reset   "\u001b[0m"
   :black   "\u001b[30m" :gray           "\u001b[1m\u001b[30m"
   :red     "\u001b[31m" :bright-red     "\u001b[1m\u001b[31m"
   :green   "\u001b[32m" :bright-green   "\u001b[1m\u001b[32m"
   :yellow  "\u001b[33m" :bright-yellow  "\u001b[1m\u001b[33m"
   :blue    "\u001b[34m" :bright-blue    "\u001b[1m\u001b[34m"
   :magenta "\u001b[35m" :bright-magenta "\u001b[1m\u001b[35m"
   :cyan    "\u001b[36m" :bright-cyan    "\u001b[1m\u001b[36m"
   :white   "\u001b[37m" :bright-white   "\u001b[1m\u001b[37m"
   :default "\u001b[39m"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config

(defn get-build-map
  [project build-key]
  (-> project :scss :builds build-key))

(defn get-arg-builds
  [project args]
  (filter #(some #{%} (-> project :scss :builds keys))
          (map read-string args)))

(defn source-dir
  [build-map]
  (let [source (or (:source-dir build-map) "scss/")]
    (if (.endsWith source "/") source (str source "/"))))

(defn dest-dir
  [build-map]
  (let [dest (or (:dest-dir build-map) "css/")]
    (if (.endsWith dest "/") dest (str dest "/"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defmacro print-time
  ([expr] `(print-time ~expr ""))
  ([expr msg & {:keys [info]}]
     `(let [start# (. System (nanoTime))
            ret# ~expr
            out-fn# (if ~info lein/info lein/debug)]
        (out-fn#
         (color :green (str "Elapsed time: "
                            (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
                            " msecs"))
         (color :bright-green (format "[%s]" ~msg)))
        ret#)))

(defn color
  [color & text]
  (if *boring*
    (string/join " " text)
    (str (ansi color) (string/join " " text) (ansi :reset))) )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path helpers

(defn abs
  [filename]
  (some-> filename io/file .getAbsoluteFile .getPath))

(defn is-scss-partial?
  [file]
  (->> file io/file .getName (re-find #"^_.+\.scss$")))

(defn is-scss?
  [file]
  (->> file io/file .getName (re-find #"^.+\.scss$")))

(defn ignore?
  [file]
  (re-find #"\.sass-cache" (.getPath file)))

(defn files-in-source-dir
  [build-map]
  (print-time
   (filter #(.isFile %) (file-seq (io/file (source-dir build-map))))
   "files-in-source-dir"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Partial helpers

(defn stylesheets-in-source
  "Get all stylesheets in source dir, not including partials"
  [build-map]
  (let [files  (files-in-source-dir build-map)
        result (->> files
                    (filter is-scss?)
                    (remove ignore?)
                    (remove is-scss-partial?))]
    (lein/debug (color :blue "Stylesheets found in" (source-dir build-map) ":"
                       (string/join " | " result)))
    result))

(defn partial-from-import-str*
  "Takes a partial string and return the actual file."
  [build-map file partial-str]
  (let [partial-str        (string/replace partial-str #"(.+?/)?([^/]+)$" "$1_$2.scss")
        relative-candidate (string/replace file #"/[^/]*?$" (str "/" partial-str))
        absolute-candidate (str (source-dir build-map) partial-str)
        file? #(let [f (-> % io/as-file)] (when (.isFile f) f))]
    (or (file? absolute-candidate) (file? relative-candidate))))
(def partial-from-import-str (memoize partial-from-import-str*))

(defn deps-in-scss
  "Get all direct dependencies for an .scss file"
  [build-map file]
  (with-open [rdr (io/reader file)]
    (->> (some->> rdr
                  line-seq
                  (map #(re-find #"@import\s+['\"](.*)['\"]" %))
                  (remove nil?)
                  (map last)
                  (map (partial partial-from-import-str build-map file)))
         (remove nil?)
         doall)))

(defn all-deps-for-scss
  "Get all dependencies for an .scss file, dependencies of partials too."
  [build-map file]
  (let [children (atom [])
        result   (distinct
                  (tree-seq (fn [child]
                              (let [get? (not (some #{child} @children))]
                                (swap! children conj child)
                                get?))
                            (partial deps-in-scss build-map) file))]
    [(abs (first result)) (map abs (rest result))]))

(defn source-tree
  [build-map]
  (pmap (partial all-deps-for-scss build-map) (stylesheets-in-source build-map)))

(defn stylesheets-with-partial
  [build-map file]
  (print-time (pmap first (filter #(some #{(abs file)} (second %)) (source-tree build-map)))
              (str "stylesheets-with-partial: " file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main

(defn convert
  [build-map file]
  (let [filename  (abs file)
        source    (abs (source-dir build-map))
        dest      (abs (dest-dir build-map))
        dest-file (string/replace filename (re-pattern (str "^" source)) "")
        css-file  (string/replace dest-file #"\.scss$" ".css")
        from      filename
        to        (str dest css-file)
        exec      (-> build-map :executable (or "sass"))
        cmd-args  (:args build-map)
        cmd       (conj
                   (cond-> [exec]
                           (not (empty? cmd-args))
                           (as-> args-list (apply conj args-list cmd-args)))
                   from to)]
    (lein/info (color :bright-blue "Converting" from "->" to))
    (lein/debug (color :blue "cmd: " (string/join " " cmd)))
    (io/make-parents to)
    (print-time (apply eval/sh cmd) (str "convert: " file))))

(defn handle-conversion
  [build-map file]
  (if (is-scss-partial? file)
    (doall
     (pmap (partial handle-conversion build-map) (stylesheets-with-partial build-map file)))
    (convert build-map file)))

(defn handle-change
  [build-map {:keys [file count action]}]
  (try
    (lein/debug (color :blue "Detected file change: [file count action]"
                       file count action))
    (when-let [file (and (is-scss? file) file)]
      (lein/info (color :bright-yellow "Detected file change:" (.getName file)))
      (print-time (handle-conversion build-map file) (str "handle-change:" file)))
    (catch Exception e (lein/warn (with-out-str (clojure.repl/pst e 20))))))

(defn watchd
  [build-map dir]
  (lein/info (color :bright-yellow "Watching directory" dir))
  (watch-dir (partial handle-change build-map) (io/file dir)))

(def watchers (atom []))

(defn auto
  [project args builds]
  (doseq [build builds
          :let [build-map (get-build-map project build)]]
    (swap! watchers conj (watchd build-map (source-dir build-map))))
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. #(do (println (color :bright-magenta "\nGoodbye!"))
                 (doseq [watcher @watchers] (close-watcher watcher)))))
  (while true
    (lein/debug (color :blue "Watchers: " @watchers))
    (Thread/sleep 20000)))

(defn once
  [project args builds]
  (doseq [build builds
          :let [build-map   (get-build-map project build)
                stylesheets (print-time (stylesheets-in-source build-map))
                exit-codes  (-> (pmap (partial convert build-map) stylesheets)
                              doall
                              (print-time "Total time" :info true))]]
    (when (not-every? #(= % 0) exit-codes)
      (lein/info (color :bright-red "There were errors compiling some of the stylesheets."))
      (System/exit 2))))

(defn scss
  "Compile all stylesheets in the source dir and/or watch for changes."
  [project & args]
  (binding [*boring* (some #{"boring"} args)]
    (lein/debug (color :blue "args: " args))
    (let [builds (get-arg-builds project args)]
      (if (seq builds)
        (do
          (when-not (some #{"auto"} args)
            (once project args builds))
          (when-not (some #{"once"} args)
            (auto project args builds)))
        (do (lein/info (color :bright-white "  Usage: lein scss <build-key ...> [auto|once] [boring]"))
            (System/exit 1))))))
