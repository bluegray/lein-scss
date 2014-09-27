(ns leiningen.scss.path
  (:require [clojure.java.io :as io]
            [leiningen.scss.config :refer :all]
            [leiningen.scss.helpers :refer [print-time]]))

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
