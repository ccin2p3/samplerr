(defproject samplerr "0.3.2-SNAPSHOT"
  :description "riemann plugin to aggregate data in a round-robin fashion to elasticsearch"
  :url "http://github.com/samplerr/samplerr"
  :license {:name "CeCILL-C"
            :url "http://www.cecill.info/licences/Licence_CeCILL-C_V1-en.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [riemann             "0.2.10"]
                 [cheshire "5.5.0"]
                 [clj-time "0.11.0"]
                 [clojurewerkz/elastisch "2.2.0"]])
