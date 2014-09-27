(ns leiningen.scss.config)

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
