# lein-scss

A Leiningen plugin to compile scss to css.

[![Clojars Project](http://clojars.org/lein-scss/latest-version.svg)](http://clojars.org/lein-scss)

## Usage

Put `[lein-scss "0.1.0"]` into the `:plugins` vector of your project.clj.

Run with `lein scss` to to compile all your stylesheets, or optionally use
`lein scss once` or `lein scss auto` to run once and exit, or watch the source for changes.

## Setup

Add a section like this:

    :scss {:source-dir "scss/"
           :dest-dir   "public/css/"
           :executable "sassc"
           :args       ["-m" "-I" "scss" "-t" "nested"]}

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
