(ns leiningen.scss
  "Compile a scss project to css using any sass convertion tool."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.stacktrace :as st]
            [juxt.dirwatch :refer [close-watcher watch-dir]]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as lein]
            [leiningen.scss.config :refer :all]
            [leiningen.scss.helpers :refer :all]
            [leiningen.scss.partials :refer :all]
            [leiningen.scss.path :refer [abs is-scss-partial?
                                         is-scss?]]))

(defn replace-urls
  [{:keys [image-url font-url]} file]
  (let [css (slurp file)
        new-css (-> css
                    (string/replace "#IMAGE-URL#" image-url)
                    (string/replace "#FONT-URL#" font-url))]
    (spit file new-css)))

(defn convert
  [{:keys [args image-url font-url] :as build-map} file]
  (let [from-file  (abs file)
        source     (abs (source-dir build-map))
        dest       (abs (dest-dir build-map))
        css-file   (-> from-file
                       (string/replace (re-pattern (str "^" source)) "")
                       (string/replace #"\.scss$" ".css"))
        to-file    (str dest css-file)
        executable (:executable build-map "sass")
        cmd        (into [executable] (conj args from-file to-file))]
    (lein/info (color :bright-blue "Converting" from-file "->" to-file))
    (lein/debug (color :blue "cmd: " (string/join " " cmd)))
    (io/make-parents to-file)
    (let [exit-code (binding [eval/*dir* (source-dir build-map)]
                      (print-time (apply eval/sh cmd) (str "convert: " file)))]
      (when (or image-url font-url)
        (print-time (replace-urls build-map to-file) (str "replace-urls: " to-file)))
      exit-code)))

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
    (catch Exception e (lein/warn (with-out-str (st/print-stack-trace e 30))))))

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
                q-fn        (if (< 10 (count stylesheets)) pmap map)
                exit-codes  (-> (q-fn (partial convert build-map) stylesheets)
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
