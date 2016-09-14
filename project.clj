(defproject samplerr "0.3.4"
  :description "riemann plugin to aggregate data in a round-robin fashion to elasticsearch"
  :url "http://github.com/samplerr/samplerr"
  :license {:name "EPL-1.0"
            :url "https://spdx.org/licenses/EPL-1.0.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [cheshire "5.5.0"]
                 [clj-time "0.11.0"]
                 [clojurewerkz/elastisch "2.2.0"]]
  :plugins [[lein-rpm "0.0.5"
             :exclusions [org.apache.maven/maven-plugin-api
                          org.codehaus.plexus/plexus-container-default
                          org.codehaus.plexus/plexus-utils
                          org.clojure/clojure
                          classworlds]]
            [org.apache.maven/maven-plugin-api "2.0"]]
            )

