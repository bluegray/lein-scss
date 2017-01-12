(ns leiningen.scss
  "Compile an scss project to css using any commandline sass convertion tool."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.stacktrace :as st]
            [clojure.string :as string]
            [juxt.dirwatch :refer [close-watcher watch-dir]]
            [leiningen.compile :as lcompile]
            [leiningen.core.main :as lein]
            [leiningen.jar :as ljar]
            [leiningen.scss.config :refer :all]
            [leiningen.scss.helpers :refer :all]
            [leiningen.scss.jar :as jar]
            [leiningen.scss.partials :refer :all]
            [leiningen.scss.path :refer [abs is-scss-partial? is-scss?]]
            [robert.hooke :as hooke]))

(defn replace-urls
  [{:keys [image-token font-token image-url font-url]} file]
  (try
    (let [css     (slurp file)
          new-css (-> css
                      (string/replace (or image-token "#IMAGE-URL#") image-url)
                      (string/replace (or font-token "#FONT-URL#") font-url))]
      (spit file new-css))
    (catch java.io.FileNotFoundException e (lein/info (color :red e)))))

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
    (io/make-parents to-file)
    (let [return    (shell/with-sh-dir (source-dir build-map)
                      (print-time (apply shell/sh cmd) (str "convert: " file)))
          exit-code (:exit return)
          stdout    (:out return)
          stderr    (:err return)]
      (when (or image-url font-url)
        (print-time (replace-urls build-map to-file) (str "replace-urls: " to-file)))
      (when-not *quiet*
        (println (now) (color :blue from-file)
                 (color :bright-white "\n       -->") (color :bright-blue to-file)))
      (when (-> stdout string/blank? not) (println (color :bright-white stdout)))
      (when (-> stderr string/blank? not) (println (color :bright-red stderr "\n")))
      (lein/debug (color :blue "cmd: " (string/join " " cmd)))
      exit-code)))

(defn handle-conversion
  [build-map args file]
  (if (is-scss-partial? file)
    (doall
     (pmap (partial handle-conversion build-map args) (stylesheets-with-partial build-map file)))
    (let [exit-code (convert build-map file)]
      (when (some #{"beep"} args) (beep (if (= exit-code 0) :success :error))))))

(defn handle-change
  [build-map args {:keys [file count action]}]
  (try
    (lein/debug (color :blue "Detected file change: [file count action]"
                       file count action))
    (when-let [file (and (is-scss? file) file)]
      (lein/info (color :bright-yellow "Detected file change:" (.getName file)))
      (print-time (handle-conversion build-map args file) (str "handle-change:" file)))
    (catch Exception e (lein/warn (with-out-str (st/print-stack-trace e 30))))))

(defn watchd
  [build-map args dir]
  (lein/info (color :bright-yellow "Watching directory" dir))
  (watch-dir (partial handle-change build-map args) (io/file dir)))

(def watchers (atom []))

(defn auto
  [project args builds]
  (lein/info (now) (color :bright-white "Running auto"))
  (doseq [build builds
          :let  [build-map (get-build-map project build)]]
    (swap! watchers conj (watchd build-map args (source-dir build-map))))
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. #(do (println (color :bright-magenta "\nGoodbye!"))
                 (doseq [watcher @watchers] (close-watcher watcher)))))
  (while true
    (lein/debug (color :blue "Watchers: " @watchers))
    (Thread/sleep 20000)))

(defn once
  [project args builds]
  (lein/info (now) (color :bright-white "Running once"))
  (doseq [build builds
          :let  [build-map   (get-build-map project build)
                 stylesheets (print-time (stylesheets-in-source build-map))
                 q-fn        (if (< 10 (count stylesheets)) pmap map)
                 exit-codes  (-> (q-fn (partial convert build-map) stylesheets)
                                 doall
                                 (print-time "Total time" :info true))]]
    (when (not-every? #(= % 0) exit-codes)
      (lein/info (color :bright-red "There were errors compiling some of the stylesheets."))
      (when (some #{"once"} args) (System/exit 2)))))

(defn unused
  [project args builds]
  (doseq [build builds
          :let  [build-map (get-build-map project build)]]
    (doseq [p (unused-partials build-map)] (lein/info p))))

(defn scss
  "Compile all stylesheets in the source dir and/or watch for changes."
  [project & args]
  (binding [*boring* (some #{"boring"} args)
            *quiet*  (some #{"quiet"} args)]
    (lein/debug (color :blue "args: " args))
    (let [builds (get-arg-builds project args)]
      (if (seq builds)
        (if (some #{"unused"} args)
          (unused project args builds)
          (do
            (when-not (some #{"auto"} args) (once project args builds))
            (when-not (some #{"once"} args) (auto project args builds))))
        (do (lein/info (color :bright-white
                              "  Usage: lein scss <build-key ...> "
                              "[auto|once] [boring] [quiet] [beep] [unused]"))
            (System/exit 1))))))

(defn compile-hook [task & args]
  (apply task args)
  (let [project (first args)
        builds  (get-in project [:scss :builds])
        target  (remove (fn [[k v]] (or (nil? (:jar v)) (false? (:jar v)))) builds)]
    (once (first args) #{} (keys target))))

(defn jar-hook [task & [project out-file filespecs]]
  (apply task [project out-file (concat filespecs (jar/filespecs project))]))

(defn activate []
  (hooke/add-hook #'lcompile/compile #'compile-hook)
  (hooke/add-hook #'ljar/write-jar #'jar-hook))
