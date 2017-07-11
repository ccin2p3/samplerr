(defproject samplerr "0.4.1"
  :description "riemann plugin to aggregate data in a round-robin fashion to elasticsearch"
  :url "http://github.com/samplerr/samplerr"
  :license {:name "EPL-1.0"
            :url "https://spdx.org/licenses/EPL-1.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.7.0"]
                 [clj-time "0.13.0"]
                 [riemann "0.2.13"]
                 [org.clojure/tools.logging "0.3.1"]
                 [cc.qbits/spandex "0.5.1"]]
  :plugins [[lein-rpm "0.0.5"
             :exclusions [org.apache.maven/maven-plugin-api
                          org.codehaus.plexus/plexus-container-default
                          org.codehaus.plexus/plexus-utils
                          org.clojure/clojure
                          classworlds]]
            [org.apache.maven/maven-plugin-api "2.0"]]
            )

