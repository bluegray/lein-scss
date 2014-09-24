(ns leiningen.scss
  "Compile a scss project to css using any sass convertion tool."
  (:require [juxt.dirwatch :refer [watch-dir close-watcher]]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as lein]
            [clojure.java.io :as io]
            [clojure.string :as string]))

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

(def state (atom "stopped"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config

(defn source-dir
  [project args]
  (or (-> project :scss :source-dir) (first args) "scss/"))

(defn dest-dir
  [project args]
  (or (-> project :scss :dest-dir) (second args) "css/"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defmacro print-time
  [expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (lein/debug
      (color :green (str "Elapsed time: "
                         (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
                         " msecs")))
     ret#))

(defn color
  [color & text]
  (str (ansi color) (string/join " " text) (ansi :reset)) )

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
  [project args]
  (filter #(.isFile %) (file-seq (io/file (source-dir project args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Partial helpers

(defn stylesheets-in-source
  "Get all stylesheets in source dir, not including partials"
  [project args]
  (let [files  (print-time (files-in-source-dir project args))
        result (->> files
                    (filter is-scss?)
                    (remove ignore?)
                    (remove is-scss-partial?))]
    (lein/debug (color :blue "Stylesheets found in" (source-dir project args) ":"
                       (string/join " | " result)))
    result))

(defn get-partial-file-from-str*
  "Takes a partial string and return the actual file."
  [s project args]
  (let [m (for [f (remove ignore? (files-in-source-dir project args))]
            [f (-> f .getPath
                   (string/replace #"/_" "/")
                   (string/replace #".scss$" "")
                   (string/replace (re-pattern (str (source-dir project args) "/")) ""))])]
    (some (fn [f] (when (re-find (re-pattern s) (second f)) (first f))) m)))
(def get-partial-file-from-str (memoize get-partial-file-from-str*))

(defn deps-in-scss
  "Get all direct dependencies for an .scss file"
  [file project args]
  (with-open [rdr (io/reader file)]
    (->> (some->> rdr
                  line-seq
                  (map #(re-find #"@import\s+['\"](.*)['\"]" %))
                  (remove nil?)
                  (map last)
                  (map #(get-partial-file-from-str % project args)))
         (remove nil?)
         doall)))

(defn all-deps-for-scss
  "Get all dependencies for an .scss file, dependencies of partials too."
  [file project args]
  (let [children (atom [])
        result   (distinct
                  (tree-seq (fn [child]
                              (let [get? (not (some #{child} @children))]
                                (swap! children conj child)
                                get?))
                            #(deps-in-scss % project args) file))]
    [(abs (first result)) (map abs (rest result))]))

(defn source-tree
  [project args]
  (pmap #(all-deps-for-scss %  project args) (stylesheets-in-source project args)))

(defn stylesheets-with-partial
  [file project args]
  (print-time (pmap first (filter #(some #{(abs file)} (second %)) (source-tree project args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main

(defn convert
  [file project args]
  (let [filename  (abs file)
        source    (abs (source-dir project args))
        dest      (abs (dest-dir project args))
        dest-file (string/replace filename (re-pattern (str "^" source)) "")
        css-file  (string/replace dest-file #"\.scss$" ".css")
        from      filename
        to        (str dest css-file)
        exec      (-> project :scss :executable (or "sass"))
        args      (-> project :scss :args)
        cmd       (conj
                   (cond-> [exec]
                           (not (empty? args))
                           (as-> args-list (apply conj args-list args)))
                   from to)]
    (lein/info (color :bright-blue "Converting" from "->" to))
    (lein/debug (color :blue "cmd: " (string/join " " cmd)))
    (io/make-parents to)
    (print-time (apply eval/sh cmd))))

(defn handle-conversion
  [file project args]
  (if (is-scss-partial? file)
    (doall
     (pmap #(handle-conversion % project args)
           (print-time (stylesheets-with-partial file project args))))
    (convert file project args)))

(defn handle-change
  [project args {:keys [file count action]}]
  (try
    (lein/debug (color :blue "Detected file change: [file count action]"
                       file count action))
    (when-let [file (and (is-scss? file) file)]
      (lein/info (color :bright-yellow "Detected file change:" (.getName file)))
      (handle-conversion file project args))
    (catch Exception e (lein/warn (with-out-str (clojure.repl/pst e 20))))))

(defn watchd
  [project args dir]
  (lein/info (color :bright-yellow "Watching directory" dir))
  (watch-dir (partial handle-change project args) (io/file dir)))

(defn scss
  "Compile all stylesheets in the source dir and optionally watch for changes."
  [project & args]
  (lein/debug (color :blue "args: " args))
  (if-let [dir (source-dir project args)]
    (if (.isDirectory (io/as-file dir))
      (do
        (when-not (some #{"auto"} args)
          (print-time (doall (pmap #(convert % project args)
                                  (print-time (stylesheets-in-source project args))))))
        (when-not (some #{"once"} args)
            (let [watcher (watchd project args dir)]
              (try
                (loop [state* (reset! state "started")]
                  (Thread/sleep 5000)
                  (recur (reset! state "running")))
                (catch Exception e (lein/warn (with-out-str (clojure.repl/pst e 20))))
                (finally (close-watcher watcher))))))
      (lein/warn dir "is not a directory"))
    (lein/warn "Please provide a directory to watch")))
