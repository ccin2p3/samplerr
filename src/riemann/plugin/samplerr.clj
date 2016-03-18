(ns riemann.plugin.samplerr
  "A riemann plugin to downsample data in a RRDTool fashion into elasticsearch"
  (:use [clojure.tools.logging :only (info error debug warn)])
  (:require [cheshire.core :as json]
            [overtone.at-at :as at]
            [clj-time.format]
            [clj-time.core]
            [clj-time.coerce]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurewerkz.elastisch.rest.bulk :as eb]
            [clojurewerkz.elastisch.rest.index :as esri]
            [clojurewerkz.elastisch.rest.document :as esrd]
            [clojurewerkz.elastisch.rest :as esr]
            [riemann.config]
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
  (apply esr/connect argv))

  
(defn make-index-timestamper [event]
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
                                    {:create {:_type index-type :_index es_index :_id id}}
                                    {:create {:_type index-type :_index es_index}}
                                    )
                                 raw)
                            raw)]
            (when (seq bulk-create-items)
              (try
                (let [res (eb/bulk conn bulk-create-items)
                      by_status (frequencies (map :status (map :create (:items res))))
                      total (count (:items res))
                      succ (filter :_version (map :create (:items res)))
                      failed (filter :error (map :create (:items res)))]
                  (info "elasticized" total " (total) " by_status " docs to " es_index "in " (:took res) "ms")
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


(defn load-index-template 
  "Loads the file into ElasticSearch as an index template."
  [template-name mapping-file]
  (esr/put (esr/index-template-url template-name)
           :body (file-as-json mapping-file)))

;;;;;;
;;;;;;
;;;;;;

(defn compare-step
  "orders a vector of hash-maps using the key :step"
  [a b]
  (- (:step a) (:step b)))

; see https://github.com/riemann/riemann/issues/563#issuecomment-181803478
(defn rammer [e & children]
  "Pushes the accumulated metrics"
  (fn stream [events]
    (let [events     (group-by #(if (not= (:metric %) nil) :ok :nil) events)
          ok-events  (:ok events)
          nil-events (:nil events)]
      (cond
        ok-events  (streams/call-rescue ok-events children)
        nil-events (let [event (first nil-events)
                         event (if (not= (:metric @e) nil) event (assoc event :state "expired"))]
                     (riemann.config/reinject event))))))

(defn fixed-time-window-folds [interval riemann_folds_func & children]
  "Fold event stream in time every interval seconds using riemann_folds_func e.g. folds/sum"
  (let [e (atom nil)]
    (streams/fill-in-last 1 {:metric nil}
      (streams/sdo
        (streams/register e)
          (streams/fixed-time-window interval
            (rammer e
              (apply streams/smap riemann_folds_func children)))))))

; cfuncs
(defn maximum
  [interval & children]
          (streams/sreduce (fn [acc event] (if (or (nil? acc) (streams/expired? acc)) event (if (>= (:metric event) (:metric acc)) event acc)))
              (streams/coalesce interval
                (streams/smap #(first %) (apply streams/sdo children)))))

(defn sum
  [interval & children]
          (streams/sreduce (fn [acc event] (if (or (nil? acc) (streams/expired? acc)) event (assoc event :metric (+ (:metric acc) (:metric event)))))
              (streams/coalesce interval
                (streams/smap #(first %) (apply streams/sdo children)))))

(defn counter
  [interval & children]
          (streams/sreduce (fn [acc event] (if (or (nil? acc) (streams/expired? acc)) (assoc event :metric 1) (assoc event :metric (+ 1 (:metric acc))))) {:metric 0}
              (streams/coalesce interval
                (streams/smap #(first %) (apply streams/sdo children)))))

(defn average
  [interval & children]
          (streams/sreduce (fn [acc event] (if (or (nil? acc) (streams/expired? acc)) (assoc event :sum (:metric event) :count 1) (assoc event :sum (+ (:sum acc) (:metric event)) :count (+ 1 (:count acc)) :time (:time acc)))) nil
            (streams/smap #(assoc % :metric (/ (:sum %) (:count %)))
              (streams/coalesce interval
                (streams/smap #(first %) (apply streams/sdo children))))))

(defn minimum
  [interval & children]
          (streams/sreduce (fn [acc event] (if (or (nil? acc) (streams/expired? acc)) event (if (<= (:metric event) (:metric acc)) event acc)))
              (streams/coalesce interval
                (streams/smap #(first %) (apply streams/sdo children)))))

(defn- to-seconds*
  "takes a clj-time duration and converts it to seconds"
  [dateobj]
  (clj-time.core/in-seconds dateobj))
(def to-seconds (memoize to-seconds*))

(defn down-n-cf
  "takes map of archive parameters and sends time-aggregated data to elasticsearch"
  [{:keys [cfunc step tf] :as args :or {cfunc {:name "avg" :func average}}} & children]
  (let [cfunc_n (:name cfunc)
        cfunc_f (:func cfunc)
        seconds (to-seconds step)]
    (streams/with {:step seconds :cfunc cfunc_n :ttl seconds :tf tf}
      (streams/where metric
        (cfunc_f seconds
          (streams/smap #(assoc % :service (str (:service %) "/" cfunc_n "/" seconds))
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
(defn indices [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

;;;
;;; alias shifting code
;;;

(defn list-indices
  "lists all indices from an elasticsearch cluster having given prefix"
  [elastic prefix]
  (map name (keys (esri/get-aliases elastic (str prefix "*")))))

(defn get-index-metadata
  "returns index metadata"
  [elastic index]
  (esrd/get elastic index "meta" "samplerr"))

(defn flagged?
  "returns true if index is flagged as expired"
  [elastic index]
  (true? ((comp :expired :_source) (get-index-metadata elastic index))))

(defn flag
  "flags index as expired"
  [elastic index]
  (esrd/put elastic index "meta" "samplerr" {:expired true}))

(defn unflag
  "flags index as expired"
  [elastic index]
  (esrd/put elastic index "meta" "samplerr" {:expired false}))

(defn index-exists?
  "returns true if index exists"
  [elastic index]
  (esri/exists? elastic index))

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

(defn parse-datestr
  "parses datestr using retention-policy"
  [datestr retention-policy]
  (let [tf (:tf retention-policy)]
    (clj-time.format/parse (clj-time.format/formatter tf) datestr)))

(defn parse-retention-policy-date
  "parses datestr using index n of retention-policies"
  [datestr retention-policies n]
  (let [retention-policy (nth retention-policies n)]
    (parse-datestr datestr retention-policy)))

(defn format-datestr
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
  (keys ((comp :aliases (keyword index))(esri/get-aliases elastic index))))

(defmacro dbg[x] `(let [x# ~x] (println "dbg:" '~x "=" x#) x#))

(defn move-aliases
  "moves aliases from src-index to dst-index"
  [elastic src-index dst-index]
  (let [src-aliases (get-aliases elastic src-index)
        src-actions (map #(hash-map :remove (hash-map :index src-index :alias %)) src-aliases)
        dst-actions (map #(hash-map :add    (hash-map :index dst-index :alias %)) src-aliases)]
    (esri/update-aliases elastic src-actions dst-actions)))

(defn add-alias
  "adds alias to index"
  [elastic index es-alias]
  (esri/update-aliases elastic {:add {:index index :alias es-alias}}))

(defn remove-aliases
  "removes all aliases from index"
  [elastic index]
  (let [aliases (get-aliases elastic index)
        actions (map #(hash-map :remove (hash-map :index index :alias %)) aliases)]
    (esri/update-aliases elastic actions)))

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
  (if (not (flagged? elastic index))
    (let [datestr (clojure.string/replace index (re-pattern (str "^" index-prefix)) "")
          retention-policy-index (get-retention-policy-index datestr retention-policies)
          dateobj (parse-retention-policy-date datestr retention-policies retention-policy-index)]
      (if retention-policy-index
        (let [next-dates (fresh-index-targets dateobj retention-policies)
              next-date (first next-dates)
              next-index (str index-prefix next-date)]
          (if (is-expired? datestr retention-policies)
            (do
              ;(flag elastic index)
              (if next-date
                (if (index-exists? elastic next-index)
                  (if (get-aliases elastic index)
                    (move-aliases elastic index next-index)
                    (add-alias elastic next-index (str alias-prefix datestr)))
                  (throw (Exception. (str "can't move aliases from " index " to missing " next-index)))))
              (if (get-aliases elastic index)
                (remove-aliases elastic index)))
              (add-alias elastic index (str alias-prefix datestr))))))))

(defn shift-aliases-with-map
  "maps shift-alias to all indices from elastic connection matching index-prefix"
  [elastic index-prefix alias-prefix retention-policies]
  (let [indices (list-indices elastic (str index-prefix "*"))]
    (map #(shift-alias elastic % index-prefix alias-prefix retention-policies) indices)))

(defn rotate
  "maps shift-alias to all indices from elastic connection matching index-prefix"
  [elastic index-prefix alias-prefix retention-policies]
  (loop [indices (list-indices elastic (str index-prefix "*"))]
    (let [current-index (first indices)
          remaining-indices (rest indices)]
      (shift-alias elastic current-index index-prefix alias-prefix retention-policies)
      (if (not (empty? remaining-indices))
        (recur remaining-indices)))))

(defn rotate-every
  "periodically rotates aliases"
  [interval elastic index-prefix alias-prefix retention-policies]
  (let [piscine (at/mk-pool)
        milliseconds (clj-time.core/in-millis interval)]
    (at/every milliseconds #(rotate elastic index-prefix alias-prefix retention-policies) piscine)))

