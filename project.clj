(defproject samplerr "0.6.8-SNAPSHOT"
  :description "riemann plugin to aggregate data in a round-robin fashion to elasticsearch"
  :url "http://github.com/samplerr/samplerr"
  :license {:name "EPL-1.0"
            :url "https://spdx.org/licenses/EPL-1.0.html"}
  :dependencies [[cc.qbits/spandex "0.8.2"]]
  :profiles {:provided
             {:dependencies
              [[cheshire "5.9.0"]
               [org.clojure/clojure "1.9.0"]
               [riemann "0.3.10"]
               [clj-time "0.14.2"]
               [org.clojure/tools.logging "1.2.1"]]}}
  :plugins [[lein-rpm "0.0.6"
             :exclusions [org.apache.maven/maven-plugin-api
                          org.codehaus.plexus/plexus-container-default
                          org.codehaus.plexus/plexus-utils
                          org.clojure/clojure
                          classworlds]]
            ; for lein-rpm
            [org.apache.maven/maven-plugin-api "2.0"]
            [org.codehaus.plexus/plexus-container-default
             "2.0.0"]
            [org.codehaus.plexus/plexus-utils "3.2.0"]
            [classworlds "1.1"]
            [test2junit "1.3.3"]]
            )

