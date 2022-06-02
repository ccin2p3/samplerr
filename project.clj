(defproject samplerr "0.6.5"
  :description "riemann plugin to aggregate data in a round-robin fashion to elasticsearch"
  :url "http://github.com/samplerr/samplerr"
  :license {:name "EPL-1.0"
            :url "https://spdx.org/licenses/EPL-1.0.html"}
  :dependencies [[cc.qbits/spandex "0.7.10"]]
  :profiles {:provided
             {:dependencies
              [[cheshire "5.7.0"]
               [org.clojure/clojure "1.8.0"]
               [riemann "0.3.1"]
               [clj-time "0.13.0"]
               [org.clojure/tools.logging "0.3.1"]]}}
  :plugins [[lein-rpm "0.0.5"
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

