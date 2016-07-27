(ns leiningen.scss.jar
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [leiningen.scss.config :as config]))

(defn relative-path [parent child]
  (let [relative (s/replace child parent "")]
    (when (= child relative)
      (throw (Exception. (str child " is not a child of " parent))))
    (s/replace relative #"^[\\/]" "")))

(defn file-bytes [file]
  (with-open [input (java.io.FileInputStream. file)]
    (let [data (byte-array (.length file))]
      (.read input data)
      data)))

(defn canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn get-files [path]
  (filter #(not (.isDirectory %)) (file-seq (io/file path))))

(defn filespecs [project]
  (let [builds     (-> project :scss :builds)
        build-maps (apply dissoc builds (keep #(-> % val (contains? :jar) (if nil (key %))) builds))
        paths      (dedupe (map #(config/dest-dir (val %)) build-maps))]
    (flatten
     (for [path paths]
       (for [file (get-files path)]
         {:type  :bytes
          :path  (relative-path (canonical-path path) (canonical-path file))
          :bytes (file-bytes file)})))))
