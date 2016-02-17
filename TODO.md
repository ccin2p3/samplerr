# TODO

## aggregation

* (fixed-offset-time-window) is too memory intensive
  for min/max/avg we don't need to store all values
→ (samplerr/scamm
    [interval & children]
    "accumulates events during fixed interval and emits exactly five events: sum, count, average, min and max
     it appends "/cfunc/interval" to service where cfunc is one of min, max, avg and count"
     (…)

### POC for min/max/avg/sum/count

* probably need to use (streams/sreduce)

```clojure
(let [index (index)]
  (streams
    (default :ttl 60
      (where metric
        (by [:host :service]
          (sreduce (fn [acc event] (if (or (nil? acc) (expired? acc)) event (if (>= (:metric event) (:metric acc)) event acc)))
            (smap #(assoc % :service (str (:service %) " max"))
              (coalesce 60
                (smap #(first %) index))))
          (sreduce (fn [acc event] (if (or (nil? acc) (expired? acc)) event (assoc event :metric (+ (:metric acc) (:metric event)))))
            (smap #(assoc % :service (str (:service %) " sum"))
              (coalesce 60
                (smap #(first %) index))))
          (sreduce (fn [acc event] (if (or (nil? acc) (expired? acc)) (assoc event :metric 1) (assoc event :metric (+ 1 (:metric acc))))) {:metric 0}
            (smap #(assoc % :service (str (:service %) " count"))
              (coalesce 60
                (smap #(first %) index))))
          (sreduce (fn [acc event] (if (or (nil? acc) (expired? acc)) (assoc event :sum (:metric event) :count 1) (assoc event :sum (+ (:sum acc) (:metric event)) :count (+ 1 (:count acc))))) nil
            (smap #(assoc % :service (str (:service %) " avg") :metric (/ (:sum %) (:count %)))
              (coalesce 60
                (smap #(first %) index))))
          (sreduce (fn [acc event] (if (or (nil? acc) (expired? acc)) event (if (<= (:metric event) (:metric acc)) event acc)))
            (smap #(assoc % :service (str (:service %) " min"))
              (coalesce 60
                (smap #(first %) index)))))))))
```

## aliasing

* (samplerr/periodically-expire 86400 [{:es_index "'samplerr-'YYYY.MM.DD" :keep 172800}
                                       {:es_index "'samplerr-'YYYY.MM"    :keep 5270400}
                                       {:es_index "'samplerr-'YYYY"       :keep -1}])

