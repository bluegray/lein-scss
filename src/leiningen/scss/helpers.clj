(ns leiningen.scss.helpers
  (:require [clj-time.core :as time]
            [clj-time.format :as format]
            [clojure.string :as string]
            [clojure.java.shell :as shell]
            [leiningen.core.main :as lein]))

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

(defn color
  [color & text]
  (if *boring*
    (string/join " " text)
    (str (ansi color) (string/join " " text) (ansi :reset))) )

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

(defn now
  []
  (color :bright-cyan (format/unparse (format/formatter "[HH:mm:ss]") (time/now))))

(defn beep
  [& args]
  (apply shell/sh (concat ["beep"] (case (first args)
                                     :success ["-f" "1660" "-l" "80"]
                                     :error ["-f" "110" "-l" "200"]
                                     nil))))
