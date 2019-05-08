(defproject chocola "2.0.0-SNAPSHOT"
  :description "A unified framework of futures, transactions, and actors."
  :url "http://soft.vub.ac.be/~jswalens/chocola/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "0.3.0"]]

  :injections [(require 'chocola.core)]

  ; Documentation about mixing Clojure and Java in a Leiningen project at
  ; https://github.com/technomancy/leiningen/blob/master/doc/MIXED_PROJECTS.md
  :source-paths ["src/clj"]
  :java-source-paths ["src/jvm"])
