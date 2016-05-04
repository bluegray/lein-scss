(ns leiningen.scss.partials
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [leiningen.core.main :as lein]
            [leiningen.scss.config :refer :all]
            [leiningen.scss.helpers :refer :all]
            [leiningen.scss.path :refer :all]))

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

(defn partial-from-import-str
  "Takes a partial string and return the actual file."
  [build-map file partial-str]
  (let [partial-str        (string/replace partial-str #"(.+?/)?([^/]+)$" "$1_$2.scss")
        relative-candidate (string/replace file #"/[^/]*?$" (str "/" partial-str))
        absolute-candidate (str (source-dir build-map) partial-str)
        file?              #(let [f (-> % io/as-file)] (when (.isFile f) f))]
    (or (file? absolute-candidate) (file? relative-candidate))))

(defn deps-in-scss
  "Get all direct dependencies for an .scss file"
  [build-map file]
  (try
    (with-open [rdr (io/reader file)]
      (->> (some->> rdr
                    line-seq
                    (map #(re-find #"@import\s+['\"](.*)['\"]" %))
                    (remove nil?)
                    (map last)
                    (map (partial partial-from-import-str build-map file)))
           (remove nil?)
           doall))
    (catch java.io.FileNotFoundException e (lein/info (color :red e)))))

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
