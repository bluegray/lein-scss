(ns leiningen.scss
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
  (or (-> project :scss :source-dir) (first args)))

(defn dest-dir
  [project args]
  (or (-> project :scss :dest-dir) (second args)))

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

(defn abs
  [filename]
  (some-> filename io/file .getAbsoluteFile .getPath))

(defn is-scss-partial?
  [file]
  (re-find #"^_.+\.scss$" (.getName file)))

(defn is-scss?
  [file]
  (re-find #"^.+\.scss$" (.getName file)))

(defn ignore?
  [file]
  (re-find #"\.sass-cache" (.getPath file)))

(defn contains-partial?
  [partial-name file]
  (when-not
      (re-find (re-pattern partial-name) (.getName file))
      (some #(re-find (re-pattern (str "@import\\s+['\"].*\\b" partial-name "['\"];")) %)
            (line-seq (io/reader file)))))

(defn files-in-source-dir
  [project args]
  (filter #(.isFile %) (file-seq (io/file (source-dir project args)))))

(defn files-with-partial
  [partial-name project args]
  (lein/debug (color :blue "Searching for files with partial" partial-name))
  (let [files   (print-time (files-in-source-dir project args))
        result  (->> files
                     (filter is-scss?)
                     (remove ignore?)
                     (filter  (partial contains-partial? partial-name)))]
    (lein/debug (color :blue "Found:" (string/join " | " result)))
    result))

(defn stylesheets-in-source
  [project args]
  (let [files  (print-time (files-in-source-dir project args))
        result (->> files
                    (filter is-scss?)
                    (remove is-scss-partial?))]
    (lein/debug (color :blue "Stylesheets found in" (source-dir project args) ":"
                       (string/join " | " result)))
    result))

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
     (map #(handle-conversion % project args)
          (print-time (files-with-partial (last (re-find #"_([^.]+)" (.getName file))) project args))))
    (convert file project args)))

(defn handle-change
  [project args {:keys [file count action]}]
  (try
    (lein/debug (color :blue "Detected file change: [file count action]"
                       file count action))
    (when-let [file (and (is-scss? file) file)]
      (lein/info (color :bright-yellow "Detected file change:" (.getName file)))
      (handle-conversion file project args))
    (catch Exception e (lein/warn e))))

(defn watchd
  [project args dir]
  (lein/info (color :bright-yellow "Watching directory" dir))
  (watch-dir (partial handle-change project args) (io/file dir)))

(defn scss
  "Watch a dir for changes and compile css"
  [project & args]
  (lein/debug (color :blue "args: " args))
  (if-let [dir (source-dir project args)]
    (if (.isDirectory (io/as-file dir))
      (do
        (when-not (some #{"auto"} args)
          (print-time (doall (map #(convert % project args)
                                  (print-time (stylesheets-in-source project args))))))
        (when-not (some #{"once"} args)
            (let [watcher (watchd project args dir)]
              (try
                (loop [state* (reset! state "started")]
                  (Thread/sleep 5000)
                  (recur (reset! state "running")))
                (catch Exception e (lein/warn e))
                (finally (close-watcher watcher))))))
      (lein/warn dir "is not a directory"))
    (lein/warn "Please provide a directory to watch")))
