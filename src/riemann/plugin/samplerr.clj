; Copyright (c) Centre de Calcul de l'IN2P3 du CNRS
; Contributor(s) : Fabien Wernli (2016)

(ns riemann.plugin.samplerr
  "A riemann plugin to downsample data in a RRDTool fashion into elasticsearch"
  (:use [clojure.tools.logging :only (info error debug warn)]
        [riemann.common :only [member?]]
        [riemann.time :only [unix-time]])
  (:require [cheshire.core :as json]
            [clj-time.format]
            [clj-time.core]
            [clj-time.coerce]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [qbits.spandex :as es]
            [riemann.config]
            [riemann.core :as core]
            [riemann.service :as service]
            [riemann.streams :as streams]))

(defn ^{:private true} keys-to-map [key-map] 
  (reduce-kv (fn [key-map k v] 
                 (assoc-in key-map 
                              (clojure.string/split (name k) #"\.")
                              (if (map? v) 
                                  (keys-to-map v) 
                                  v))) 
                                {} key-map))


(def ^{:private true} format-iso8601
  (clj-time.format/with-zone (clj-time.format/formatters :date-time-no-ms)
    clj-time.core/utc))

(defn ^{:private true} iso8601 [event-s]
  (clj-time.format/unparse format-iso8601
                           (clj-time.coerce/from-long (* 1000 event-s))))

(defn ^{:private true} safe-iso8601 [event-s]
  (try (iso8601 event-s)
    (catch Exception e
      (warn "Unable to parse iso8601 input: " event-s)
      (clj-time.format/unparse format-iso8601 (clj-time.core/now)))))

(defn ^{:private true} stashify-timestamp [event]
  (->  (if-not (get event "@timestamp")
         (let [time (:time event)]
           (if (nil? time) (info event))
           (assoc event "@timestamp" (safe-iso8601 (long time))))
         event)
       (dissoc :time)
       (dissoc :ttl)))

(defn ^{:private true} edn-safe-read [v]
  (try
    (edn/read-string v)
    (catch Exception e
      (warn "Unable to read supposed EDN form with value: " v)
      v)))

(defn ^{:private true} message-event [event]
  (keys-to-map
    (into {}
        (for [[k v] event
              :when v]
          (cond
           (= (name k) "_id") [k v]
           (.startsWith (name k) "_")
           [(.substring (name k) 1) (edn-safe-read v)]
           :else
           [k v])))))

(defn ^{:private true} elastic-event [event message]
  (let [e (-> event
              stashify-timestamp)]
    (if message
      (message-event e)
      e)))

(defn ^{:private true} riemann-to-elasticsearch [events message]
  (->> [events]
       flatten
       (remove streams/expired?)
       (map #(elastic-event % message))))

(defn connect
  "Connect to ElasticSearch"
  [& argv]
  (apply es/client argv))

  
(defn- make-index-timestamper [event]
  (let [formatter (clj-time.format/formatter (eval (get event "tf")))]
    (fn [date]
      (clj-time.format/unparse formatter date))))

(defn persist
  "bulk index to ES"
  [{:keys [conn index-type index-prefix message]
               :or {index-type "samplerr" index-prefix ".samplerr"
                    message true}} & children]
    (fn [events]
      (let [esets (group-by (fn [e] (let [index-namer (make-index-timestamper e)]
                              (str index-prefix (index-namer
                               (clj-time.format/parse format-iso8601 
                                                      (get e "@timestamp"))))))
                            (riemann-to-elasticsearch events message))]
        (doseq [es_index (keys esets)]
          (let [raw (get esets es_index)
                bulk-create-items
                (interleave (map #(if-let [id (get % "_id")]
                                    {:index {:_type index-type :_index es_index :_id id}}
                                    {:index {:_type index-type :_index es_index}}
                                    )
                                 raw)
                            raw)]
            (when (seq bulk-create-items)
              (try
                (let [response (es/request conn {:url "_bulk" :body (es/chunks->body bulk-create-items) :content-type "application/x-ndjson" :method :post})
                      res (:body response)
                      by_status (frequencies (map :status (map :index (:items res))))
                      total (count (:items res))
                      succ (filter :_version (map :index (:items res)))
                      failed (filter :error (map :index (:items res)))]
                  (debug "elasticized" total " (total) " by_status " docs to " es_index "in " (:took res) "ms")
                  (debug "Failed: " failed))
                (catch Exception e
                  (error "Unable to bulk index:" e)))))))))

(defn ^{:private true} resource-as-json [resource-name]
  (json/parse-string (slurp (io/resource resource-name))))


(defn ^{:private true} file-as-json [file-name]
  (try
    (json/parse-string (slurp file-name))
    (catch Exception e
      (error "Exception while reading JSON file: " file-name)
      (throw e))))


;;;;;;
;;;;;;
;;;;;;

(defn- new-interval?
  [acc event]
  (when-let [acc-time (:time acc)]
    (let [event-time (:time event)
          age (- event-time acc-time)
          step (:step event)]
      (>= age step))))

; cfuncs
(defn sum
  [interval & children]
  (streams/sreduce
    (fn [acc event]
      (if (nil? acc)
        event
        (if (new-interval? acc event)
          (assoc event :parent (dissoc acc :parent))
          (assoc event :time (:time acc) :metric (+ (:metric event) (:metric acc))))))
    nil
    (streams/where (contains? event :parent)
      (streams/smap #((comp (fn [e] (dissoc e :parent))
                            :parent) %)
        (apply streams/sdo children)))))


(defn counter
  [interval & children]
  (streams/sreduce
    (fn [acc event]
      (if (nil? acc)
          (assoc event :metric 1)
          (if (new-interval? acc event)
              (assoc event :metric 1 :parent (dissoc acc :parent))
              (assoc event :time (:time acc) :metric (+ 1 (:metric acc))))))
    nil
    (streams/where (contains? event :parent)
      (streams/smap #((comp (fn [event] (dissoc event :parent)) :parent) %)
        (apply streams/sdo children)))))

(defn average
  [interval & children]
  (streams/sreduce
    (fn [acc event]
      (if (nil? acc)
        (assoc event :sum (:metric event) :count 1)
        (if (new-interval? acc event)
          (assoc event :sum (:metric event) :count 1 :parent (dissoc acc :parent))
          (assoc event :time (:time acc) :count (+ 1 (:count acc)) :sum (+ (:metric event) (:sum acc))))))
    nil
    (streams/where (contains? event :parent)
      (streams/smap #((comp (fn [e] (assoc e :metric (/ (:sum e) (:count e))))
                            (fn [e] (dissoc e :parent))
                            :parent) %)
        (apply streams/sdo children)))))

(defn extremum
  [efunc interval children]
  (streams/sreduce
    (fn [acc event]
      (if (new-interval? acc event)
        (assoc event :parent (dissoc acc :parent) :orig-time (:time event))
        (if (efunc (:metric event) (:metric acc))
          (assoc event :time (:time acc) :orig-time (:time event))
          acc)))
    (streams/where (contains? event :parent)
      (streams/smap #((comp (fn [e] (dissoc e :orig-time))
                            (fn [e] (assoc e :time ((some-fn :orig-time :time) e)))
                            (fn [e] (dissoc e :parent))
                            :parent) %)
        (apply streams/sdo children)))))

(defn maximum
  [interval & children]
  (extremum >= interval children))

(defn minimum
  [interval & children]
  (extremum <= interval children))

(defn- to-seconds*
  "takes a clj-time duration and converts it to seconds"
  [dateobj]
  (clj-time.core/in-seconds dateobj))
(def to-seconds (memoize to-seconds*))

(defn- to-millis*
  "takes a clj-time duration and converts it to milliseconds"
  [dateobj]
  (clj-time.core/in-millis dateobj))
(def to-millis (memoize to-millis*))

(defn down-n-cf
  "takes map of archive parameters and sends time-aggregated data to children"
  [{:keys [cfunc step tf] :as args :or {cfunc {:name "avg" :func average}}} & children]
  (let [cfunc_n (:name cfunc)
        cfunc_f (:func cfunc)
        seconds (to-seconds step)]
    (streams/with {:step seconds :cfunc cfunc_n :tf tf}
      (streams/where metric
        (cfunc_f seconds
          (streams/smap #(assoc % :ttl (max (* seconds 3) (or (:ttl %) 0)) :service (str (:service %) "/" cfunc_n "/" seconds))
            (apply streams/sdo children)))))))

(defn down-n
  "takes map of archive parameters and maps to down-n-cf for all cfuncs"
  [{:keys [cfunc] :as args} & children]
    (apply streams/sdo (map #(apply down-n-cf (assoc args :cfunc %) children) cfunc)))

(defn down
  "takes vector of archives and generates (count vector) down-n streams"
  [archives & children]
  (apply streams/sdo (map #(apply down-n % children) archives)))

;;;
;;; foreign commodity functions
;;; that need license checking

;source http://stackoverflow.com/questions/8641305/find-index-of-an-element-matching-a-predicate-in-clojure
(defn- indices [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

;;;
;;; alias shifting code
;;;

(defn list-indices
  "lists all indices from an elasticsearch cluster having given prefix"
  [elastic prefix]
  (map name (keys (:indices (:body (es/request elastic {:url [(str prefix "*") :_stats :store] :method :get}))))))

(defn- index-exists?
  "returns true if index exists"
  [elastic index]
  (try
    (es/request elastic {:url [index] :method :head})
    (catch Exception e
      (warn e "index exists caught"))))

(defn matches-timeformat?
  "returns true if datestr matches timeformat"
  [datestr timeformat]
  (try (clj-time.format/parse (clj-time.format/formatter timeformat) datestr) (catch IllegalArgumentException ex false)))

; TODO: should return an additional key which is the parsed clj-time object so we don't need to parse time again
(defn get-retention-policy
  "returns the first retention policy that matches datestr or nil
  example: (get-retention-policy \"2016\" [{:tf \"YYYY.MM.DD\" :ttl 86400} {:tf \"YYYY\" :ttl 315567360}])
  will return {:tf \"YYYY\" :ttl 315567360}"
  [datestr retention-policies]
  (first (filter #(matches-timeformat? datestr (:tf %)) retention-policies)))

(defn get-retention-policy-index
  "returns the first retention policy's index that matches datestr or nil
  example: (get-retention-policy \"2016\" [{:tf \"YYYY.MM.DD\" :ttl 86400} {:tf \"YYYY\" :ttl 315567360}])
  will return {:tf \"YYYY\" :ttl 315567360}"
  [datestr retention-policies]
  (first (indices #(matches-timeformat? datestr (:tf %)) retention-policies)))

(defn- parse-datestr
  "parses datestr using retention-policy"
  [datestr retention-policy]
  (let [tf (:tf retention-policy)]
    (clj-time.format/parse (clj-time.format/formatter tf) datestr)))

(defn parse-retention-policy-date
  "parses datestr using index n of retention-policies"
  [datestr retention-policies n]
  (if n
    (let [retention-policy (nth retention-policies n)]
      (parse-datestr datestr retention-policy))
    (warn (str datestr " does not match any retention policy"))))

(defn- format-datestr
  "formats dateobj using retention-policy"
  [dateobj retention-policy]
  (let [tf (:tf retention-policy)]
    (clj-time.format/unparse (clj-time.format/formatter tf) dateobj)))

(defn format-retention-policy-date
  "formats dateobj using index n of retention-polices"
  [dateobj retention-policies n]
  (let [retention-policy (nth retention-policies n)]
    (format-datestr dateobj retention-policy)))

(defn is-expired?
  "returns true if datestr matches an expired retention policy"
  [datestr retention-policies]
  (let [retention-policy (get-retention-policy datestr retention-policies)
        tf (:tf retention-policy)
        ttl (:ttl retention-policy)
        parsed-time (clj-time.format/parse (clj-time.format/formatter tf) datestr)
        now (clj-time.core/now)
        expiration (clj-time.core/minus now ttl)]
    (clj-time.core/before? parsed-time expiration)))

(defn get-aliases
  "returns aliases of index or empty list"
  [elastic index]
  (keys ((comp :aliases (keyword index))(:body (es/request elastic {:url [index :_alias] :method :get})))))

(defn move-aliases
  "moves aliases from src-index to dst-index"
  [elastic src-index dst-index]
  (info "transfer aliases from" src-index "to" dst-index)
  (let [src-aliases (get-aliases elastic src-index)
        src-actions (map #(hash-map :remove (hash-map :index src-index :alias %)) src-aliases)
        dst-actions (map #(hash-map :add    (hash-map :index dst-index :alias %)) src-aliases)]
    (es/request elastic {:url "/_aliases" :method :post :body {:actions (vec (concat src-actions dst-actions))}})))

(defn add-alias
  "adds alias to index"
  [elastic index es-alias]
  (info "add alias" es-alias "->" index)
  (es/request elastic {:url "/_aliases" :method :post :body {:actions [{:add {:index index :alias es-alias}}]}}))

(defn remove-aliases
  "removes all aliases from index"
  [elastic index]
  (info "remove all aliases from" index)
  (let [aliases (get-aliases elastic index)
        actions (map #(hash-map :remove (hash-map :index index :alias %)) aliases)
        actions {:actions actions}]
    (es/request elastic {:url "/_aliases" :method :post :body actions})))

(defn fresh-index-targets
  "returns collection of unexpired datestrings for dateobj"
  [dateobj retention-policies]
  (for [policy retention-policies 
        :let [now (clj-time.core/now) 
              tf (:tf policy)
              ttl (:ttl policy)]
        :when (clj-time.core/before? (clj-time.core/minus now ttl) dateobj)]
    (clj-time.format/unparse (clj-time.format/formatter tf) dateobj)))

(defn shift-alias
  "matches index with its corresponding retention policy. if expired, shifts its existing aliases to next retention policy. else adds alias if necessary"
  [elastic index index-prefix alias-prefix retention-policies]
  (let [datestr (clojure.string/replace index (re-pattern (str "^" index-prefix)) "")
        retention-policy-index (get-retention-policy-index datestr retention-policies)
        dateobj (parse-retention-policy-date datestr retention-policies retention-policy-index)]
    (if retention-policy-index
      (let [next-dates (fresh-index-targets dateobj retention-policies)
            next-date (first next-dates)
            next-index (str index-prefix next-date)]
        (if (is-expired? datestr retention-policies)
          (do
            (if next-date
              (if (index-exists? elastic next-index)
;;;;;;;stacktrace happens here
;;;;;;;at clojure.core$ex_info.invokeStatic(core.clj:4617) at clojure.core$ex_info.invoke(core.clj:4617) at qbits.spandex$response_ex__GT_ex_info.invokeStatic(spandex.clj:218) at qbits.spandex$response_ex__GT_ex_info.invoke(spandex.clj:215) at qbits.spandex$eval6897$fn__6898.invoke(spandex.clj:227) at qbits.spandex$eval6880$fn__6881$G__6871__6886.invoke(spandex.clj:222) at qbits.spandex$default_exception_handler.invokeStatic(spandex.clj:238) at qbits.spandex$default_exception_handler.invoke(spandex.clj:231) at qbits.spandex$request.invokeStatic(spandex.clj:271) at qbits.spandex$request.invoke(spandex.clj:253) at riemann.plugin.samplerr$index_exists_QMARK_.invokeStatic(samplerr.clj:279) at riemann.plugin.samplerr$index_exists_QMARK_.invoke(samplerr.clj:276) at riemann.plugin.samplerr$shift_alias.invokeStatic(samplerr.clj:388) at riemann.plugin.samplerr$shift_alias.invoke(samplerr.clj:375) at riemann.plugin.samplerr$rotate.invokeStatic(samplerr.clj:454) at riemann.plugin.samplerr$rotate.invoke(samplerr.clj:447) at riemann.plugin.samplerr$rotation_service$rot__7675.invoke(samplerr.clj:470) at riemann.service.ThreadService$thread_service_runner__6478$fn__6479.invoke(service.clj:71) at riemann.service.ThreadService$thread_service_runner__6478.invoke(service.clj:70) at clojure.lang.AFn.run(AFn.java:22) at java.lang.Thread.run(Thread.java:748)
                (if (get-aliases elastic index)
                  (move-aliases elastic index next-index)
                  (add-alias elastic next-index (str alias-prefix datestr)))
                (throw (Exception. (str "can't move aliases from " index " to missing " next-index)))))
            (if (get-aliases elastic index)
              (remove-aliases elastic index)))
          (add-alias elastic index (str alias-prefix datestr)))))))

(defn delete-index
  "deletes index"
  [elastic index]
  (info "delete index" index)
  (es/request elastic {:url [index] :method :delete}))

(defn purge-index
  "deletes index if it matches a timeformat in retention-policies and is expired"
  [elastic index index-prefix retention-policies]
  (let [datestr (clojure.string/replace index (re-pattern (str "^" index-prefix)) "")
        retention-policy-index (get-retention-policy-index datestr retention-policies)
        dateobj (parse-retention-policy-date datestr retention-policies retention-policy-index)]
    (if retention-policy-index
      (if (is-expired? datestr retention-policies)
        (delete-index elastic index)))))

(defn purge
  "deletes all indices matching index-prefix and that are expired according to retention-policies"
  [{:keys [conn index-prefix archives]}]
  (debug "purging")
  (loop [indices (list-indices conn (str index-prefix "*"))]
    (let [current-index (first indices)
          remaining-indices (rest indices)]
      (purge-index conn current-index index-prefix archives)
      (if (not (empty? remaining-indices))
        (recur remaining-indices)))))

(defn purge-service
  "returns a service which schedules a task to purge indices"
  [{:keys [interval conn index-prefix archives enabled?]
    :or {interval (clj-time.core/days 3)
         enabled? true}}]
  (let [interval (to-millis interval)]
    (service/thread-service
      ::samplerr-purge [interval conn index-prefix archives enabled?]
      (fn pur [core]
        (Thread/sleep interval)
        (try
          (if enabled?
            (purge {:conn conn :index-prefix index-prefix :archives archives}))
          (catch Exception e
            (warn e "purge service caught")))))))

(defn periodically-purge
  "adds an index purge service to core"
  [& opts]
  (info "registering purge service with" (apply :interval opts) "interval")
  (let [service (apply purge-service opts)]
    (swap! riemann.config/next-core core/conj-service service :force)))

(defn rotate
  "maps shift-alias to all indices from elastic connection matching index-prefix"
  [{:keys [conn index-prefix alias-prefix archives]}]
  (debug "rotating")
  (loop [indices (list-indices conn (str index-prefix "*"))]
    (let [current-index (first indices)
          remaining-indices (rest indices)]
      (shift-alias conn current-index index-prefix alias-prefix archives)
      (if (not (empty? remaining-indices))
        (recur remaining-indices)))))

(defn rotation-service
  "returns a service which schedules a task to rotate aliases"
  [{:keys [interval conn alias-prefix index-prefix archives enabled?]
    :or {interval (clj-time.core/minutes 5)
         enabled? true}}]
  (let [interval (to-millis interval)]
    (service/thread-service
      ::samplerr-rotation [interval conn alias-prefix index-prefix archives enabled?]
      (fn rot [core]
        (Thread/sleep interval)
        (try
          (if enabled?
            (rotate {:conn conn :index-prefix index-prefix :alias-prefix alias-prefix :archives archives}))
          (catch Exception e
            (warn e "rotation service caught")))))))

(defn periodically-rotate
  "adds an alias rotation service to core"
  [& opts]
  (info "registering rotation service with" (apply :interval opts) "interval")
  (let [service (apply rotation-service opts)]
    (swap! riemann.config/next-core core/conj-service service :force)))

