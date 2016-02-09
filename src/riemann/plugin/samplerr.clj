(ns riemann.plugin.samplerr
  "A riemann plugin to downsample data in a RRDTool fashion into elasticsearch"
  (:use [clojure.tools.logging :only (info error debug warn)])
  (:require [cheshire.core :as json]
            [clj-time.format]
            [clj-time.core]
            [clj-time.coerce]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurewerkz.elastisch.rest.bulk :as eb]
            [clojurewerkz.elastisch.rest :as esr]
            [riemann.streams :as streams]))

(defn ^{:private true} keys-to-map [key-map] 
  (reduce-kv (fn [key-map k v] 
                 (assoc-in key-map 
                              (clojure.string/split (name k) #"\.")
                              (if (map? v) 
                                  (keys-to-map v) 
                                  v))) 
                                {} key-map))

(defn make-index-timestamper [index]
  (let [period :day formatter (clj-time.format/formatter (str "'" index "'"
                                  (cond
                                   (= period :day)
                                   "-YYYY.MM.dd"
                                   (= period :hour)
                                   "-YYYY.MM.dd.HH"
                                   (= period :week)
                                   "-xxxx.'W'ww"
                                   (= period :month)
                                   "-YYYY.MM"
                                   (= period :year)
                                   "-YYYY")))]
    (fn [date]
      (clj-time.format/unparse formatter date))))

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

(defn es-dummy
  "bulk index to ES"
  [{:keys [es_conn es_type es_index timestamping message]
    :as foo
               :or {es_index "sampler"
                    es_type "sampler"
                    message true
                    timestamping :day}} children]
  (streams/with foo children))
  
(defn es-index
  "bulk index to ES"
  [{:keys [es_conn es_type es_index timestamping message]
               :or {es_index "sampler"
                    es_type "sampler"
                    message true
                    timestamping :day}} & children]
  (let [index-namer (make-index-timestamper es_index)]
    (fn [events]
      (let [esets (group-by (fn [e] 
                              (index-namer 
                               (clj-time.format/parse format-iso8601 
                                                      (get e "@timestamp"))))
                            (riemann-to-elasticsearch events message))]
        (do
        (doseq [es_index (keys esets)]
          (let [raw (get esets es_index)
                bulk-create-items
                (interleave (map #(if-let [id (get % "_id")]
                                    {:create {:_type es_type :_id id}}
                                    {:create {:_type es_type}}
                                    )
                                 raw)
                            raw)]
            (when (seq bulk-create-items)
              (try
                (let [res (eb/bulk-with-index es_conn es_index bulk-create-items)
                      total (count (:items res))
                      succ (filter :ok (:items res))
                      failed (filter :error (:items res))]
                  (info "elasticized" total "/" (count succ) "/" (count failed) " (total/succ/fail) items to index " es_index "in " (:took res) "ms")
                  (debug "Failed: " failed))
                (catch Exception e
                  (error "Unable to bulk index:" e))))))) (streams/call-rescue events children)))))

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

(defn aggregate
  "aggregate data"
  [{:keys [period es_index es_type step]}]
  (es-index {:es_type es_type :timestamping period :es_index es_index}))

(defn bulk
  "bulk insert into elasticsearch"
  [{:keys [rra] :as config}]
  (map es-index (:rra config)))

;;;;;;
;;;;;;
;;;;;;

(defn compare-step
  "orders a vector of hash-maps using the key :step"
  [a b]
  (- (:step a) (:step b)))

;(defn index
;  "indexes events to elasticsearch to all round robin archives"
;  [{:keys [url] :as config}]
;  (let [conn (esr/connect url)]
;    (map agg (sort compare-step (:rra config)))))

;;;;
;;;;
;;;;
;;;;

(defn archive-n
  "takes map of archive parameters and sends time-aggregated data to elasticsearch"
  [{:keys [cfunc step keep] :as args :or {cfunc {:name "average" :func riemann.folds/mean}}} & children]
  (let [cfunc_n (:name cfunc)
        cfunc_f (:func cfunc)]
    (streams/with {:samplerr.step step :samplerr.keep keep :samplerr.cfunc cfunc_n :ttl step}
      (streams/by [:host :service]
        ;(fold-interval-metric step cfunc_f (where service prn))))))
        (streams/fixed-offset-time-window step
          (streams/smap cfunc_f
            (streams/batch 100 10
              (es-index (select-keys args [:es_index :es_type :es_conn])))))))))
              ;(apply streams/sdo children))))))))

(defn archive
  "takes vector of archives and generates (count vector) archive-n streams"
  [{:keys [rra] :or {rra [{:step 10 :keep 86400}{:step 600 :keep 315567360}]}} & children]
  (apply streams/sdo (map #(apply archive-n % children) rra)))

