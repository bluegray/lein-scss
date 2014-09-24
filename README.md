# lein-scss

A Leiningen plugin to compile scss to css.

[![Clojars Project](http://clojars.org/lein-scss/latest-version.svg)](http://clojars.org/lein-scss)

## Usage

Put `[lein-scss "0.1.1"]` into the `:plugins` vector of your project.clj.

Run with:

    lein scss [source-dir] [dest-dir] [once|auto]

The source and destination directories can also be specified in your project.clj, see below.

The `auto` option watches the source directory for changes and automatically compiles them.  
The `once` option will compile all stylesheets and exit.  
Running `lein scss` without options will compile all stylesheets in the source directory and then wait for changes.

## Setup

An example project.clj would look like this:
```clojure
(defproject myproject "0.1.0-SNAPSHOT"
  :description "My Project"
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :plugins      [[lein-scss "0.1.1"]]

  :scss {:source-dir "scss/"
         :dest-dir   "public/css/"
         :executable "sassc"
         :args       ["-m" "-I" "scss" "-t" "nested"]})
```

* `:source-dir` is the directory containing your `.scss` source files.
* `:dest-dir` is the directory where `.css` files will be generated.
* `:executable` is the path to your sass conversion binary.
* `:args` is a vector of arguments to add to the command.

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
