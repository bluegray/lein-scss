# lein-scss

A Leiningen plugin to compile an scss project to css using CLI tools.
Designed to be used with the libsass binary sassc, but should work equally well with any
other CLI binary that converts scss to css.

[![Clojars Project](https://img.shields.io/clojars/v/lein-scss.svg)](https://clojars.org/lein-scss)

## Usage

Put `[lein-scss "0.2.4"]` into the `:plugins` vector of your project.clj.

Run with:

    lein scss <build-keys ...> [once|auto] [boring] [quiet] [beep]

- The `auto` option watches the source directory for changes and automatically compiles them.
- The `once` option will compile all stylesheets and exit.
- Add the `boring` option to prevent color output for use in logs.
- Add the `quiet` option to prevent excessive logging.
- Running without these options will compile all stylesheets in the source directory and then wait for changes.
- `build-keys` can be one or more keywords for builds specified in the **project.clj** configuration, see below.

## Setup

An example project.clj would look like this:
```clojure
(defproject myproject "0.1.0-SNAPSHOT"
  :description "My Project"
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :plugins      [[lein-scss "0.2.0"]]

  :scss {:builds
         {:develop    {:source-dir "scss/"
                       :dest-dir   "public/css/"
                       :executable "sassc"
                       :args       ["-m" "-I" "scss/" "-t" "nested"]}
          :production {:source-dir "scss/"
                       :dest-dir   "public/css/"
                       :executable "sassc"
                       :args       ["-I" "scss/" "-t" "compressed"]}
          :testremote {:source-dir "scss/"
                       :dest-dir   "public/css/"
                       :executable "sassc"
                       :args       ["-I" "scss/" "-t" "nested"]
                       :image-token "#IMAGE-URL#"
                       :image-url "https://s3.amazonaws.com/test/"
                       :font-token "#FONT-URL#"
                       :font-url "https://s3.amazonaws.com/test/fonts/"}
          :test       {:source-dir "tests/scss/"
                       :dest-dir   "/tmp/test/css/"
                       :executable "sassc"
                       :args       ["-m" "-I" "scss/" "-t" "nested"]}}}
```

* `:source-dir` is the directory containing your `.scss` source files.
* `:dest-dir` is the directory where `.css` files will be generated.
* `:executable` is the path to your sass conversion binary.
* `:args` is a vector of arguments to add to the command. The input and output file arguments will be appended to this list.

When specified, `:image-token` and `:font-token` will be replaced by `:image-url` and `:font-url` in the generated css.

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
