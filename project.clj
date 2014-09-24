(defproject lein-scss "0.1.2-SNAPSHOT"
  :description "A lein plugin to compile scss to css."
  :url "https://github.com/bluegray/lein-scss"
  :scm {:name "git"
        :url "https://github.com/bluegray/lein-scss"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :signing {:gpg-key "bluegray"}
  :deploy-repositories [["releases" :clojars {:creds :gpg}]]
  :eval-in-leiningen true
  :dependencies [[juxt/dirwatch "0.2.0"]])
