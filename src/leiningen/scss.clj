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
  (or (:source-dir build-map) "scss/"))

(defn dest-dir
  [build-map]
  (or (:dest-dir build-map) "css/"))

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
  [build-map]
  (filter #(.isFile %) (file-seq (io/file (source-dir build-map)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Partial helpers

(defn stylesheets-in-source
  "Get all stylesheets in source dir, not including partials"
  [build-map]
  (let [files  (print-time (files-in-source-dir build-map))
        result (->> files
                    (filter is-scss?)
                    (remove ignore?)
                    (remove is-scss-partial?))]
    (lein/debug (color :blue "Stylesheets found in" (source-dir build-map) ":"
                       (string/join " | " result)))
    result))

(defn get-partial-file-from-str*
  "Takes a partial string and return the actual file."
  [build-map partial-str]
  (let [m (for [f (remove ignore? (files-in-source-dir build-map))]
            [f (-> f .getPath
                   (string/replace #"/_" "/")
                   (string/replace #".scss$" "")
                   (string/replace (re-pattern (str (source-dir build-map) "/")) ""))])]
    (some (fn [f] (when (re-find (re-pattern partial-str) (second f)) (first f))) m)))
(def get-partial-file-from-str (memoize get-partial-file-from-str*))

(defn deps-in-scss
  "Get all direct dependencies for an .scss file"
  [build-map file]
  (with-open [rdr (io/reader file)]
    (->> (some->> rdr
                  line-seq
                  (map #(re-find #"@import\s+['\"](.*)['\"]" %))
                  (remove nil?)
                  (map last)
                  (map (partial get-partial-file-from-str build-map)))
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
  (print-time (pmap first (filter #(some #{(abs file)} (second %)) (source-tree build-map)))))

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
    (print-time (apply eval/sh cmd))))

(defn handle-conversion
  [build-map file]
  (if (is-scss-partial? file)
    (doall
     (pmap (partial handle-conversion build-map)
           (print-time (stylesheets-with-partial build-map file))))
    (convert build-map file)))

(defn handle-change
  [build-map {:keys [file count action]}]
  (try
    (lein/debug (color :blue "Detected file change: [file count action]"
                       file count action))
    (when-let [file (and (is-scss? file) file)]
      (lein/info (color :bright-yellow "Detected file change:" (.getName file)))
      (handle-conversion build-map file))
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
          :let [build-map (get-build-map project build)]]
    (-> (pmap (partial convert build-map)
              (print-time (stylesheets-in-source build-map)))
        doall
        print-time)))

(defn scss
  "Compile all stylesheets in the source dir and/or watch for changes."
  [project & args]
  (lein/debug (color :blue "args: " args))
  (let [builds (get-arg-builds project args)]
    (do
      (when-not (some #{"auto"} args)
        (once project args builds))
      (when-not (some #{"once"} args)
        (auto project args builds)))))
