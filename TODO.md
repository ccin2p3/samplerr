# TODO

##

21:49 < aphyr> righto
21:49 < aphyr> yeah, so I'd say for stuff like sum, for instance
21:50 < aphyr> (defn sum [interval & cs] (let [count (atom 0)] (fn stream [event] (swap! state + (:metric event)) ...))
21:50 < aphyr> same pattern as rate, sreduce itself, etc
21:50 < aphyr> close over an atom with your stream fn
21:51 < aphyr> https://github.com/riemann/riemann/blob/master/src/riemann/streams.clj#L220-L223
21:51 < aphyr> https://github.com/riemann/riemann/blob/master/src/riemann/streams.clj#L263-L274
21:51 < aphyr> hopefully a straightforward pattern to apply to your use case

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

* use filtered aliases in ES to avoid having duplicate entries e.g. for daily indices between 2016.04 and 2016.04.23 when search query spans multiple days
* sort retention-policies
* verify overlap between periods

* (samplerr/periodically-shift <periodicity> <index-prefix> <alias-prefix> <retention-policies>)

* (samplerr/periodically-shift 86400 samplerr-i samplerr-a [{:es_index "YYYY.MM.DD" :keep 172800}
                                                            {:es_index "YYYY.MM"    :keep 5270400}
                                                            {:es_index "YYYY"}])
* list indices

(list-indices prefix)

(require '[clojurewerkz.elastisch.rest.index :as esri])
(def elastic (samplerr/connect "http://localhost:9200"))

(keys (esri/get-aliases elastic "samplerr-*"))

(def

## Exceptions seen in the wild

```
{
  "@message": "riemann.streams$smap$stream__6995@34faf7e5 threw",
  "@timestamp": "2018-09-13T12:27:51.797Z",
  "@source_host": "ccosvms0243",
  "@fields": {
    "exception": {
      "stacktrace": "at clojure.lang.Numbers.ops(Numbers.java:1018)\nat clojure.lang.Numbers.gt(Numbers.java:234)\nat clojure.lang.Numbers.max(Numbers.java:4052)\nat riemann.plugin.samplerr$down_n_cf$fn__9606.invoke(samplerr.clj:246)\nat riemann.streams$smap$stream__6995.invoke(streams.clj:165)\nat riemann.streams$smap$stream__6995$fn__7010.invoke(streams.clj:167)\nat riemann.streams$smap$stream__6995.invoke(streams.clj:167)\nat riemann.plugin.samplerr$average$stream__9097__auto____9464$fn__9469.invoke(samplerr.clj:195)\nat riemann.plugin.samplerr$average$stream__9097__auto____9464.invoke(samplerr.clj:195)\nat riemann.streams$sreduce$stream__7191$fn__7206.invoke(streams.clj:234)\nat riemann.streams$sreduce$stream__7191.invoke(streams.clj:234)\nat riemann.plugin.samplerr$down_n_cf$stream__9097__auto____9608$fn__9613.invoke(samplerr.clj:244)\nat riemann.plugin.samplerr$down_n_cf$stream__9097__auto____9608.invoke(samplerr.clj:244)\nat riemann.streams$with$stream__8692$fn__8731.invoke(streams.clj:1343)\nat riemann.streams$with$stream__8692.invoke(streams.clj:1343)\nat riemann.streams$sdo$stream__7222$fn__7237.invoke(streams.clj:246)\nat riemann.streams$sdo$stream__7222.invoke(streams.clj:246)\nat riemann.streams$by_fn$stream__8942$fn__8947.invoke(streams.clj:1551)\nat riemann.streams$by_fn$stream__8942.invoke(streams.clj:1551)\nat riemann.config$eval9768$stream__9097__auto____9964$fn__9993.invoke(riemann.config:48)\nat riemann.config$eval9768$stream__9097__auto____9964.invoke(riemann.config:48)\nat riemann.config$eval9768$stream__9097__auto____10024$fn__10053.invoke(riemann.config:44)\nat riemann.config$eval9768$stream__9097__auto____10024.invoke(riemann.config:44)\nat riemann.config$eval9768$stream__9097__auto____10143$fn__10148.invoke(riemann.config:43)\nat riemann.config$eval9768$stream__9097__auto____10143.invoke(riemann.config:43)\nat riemann.core$stream_BANG_$fn__9746.invoke(core.clj:20)\nat riemann.core$stream_BANG_.invokeStatic(core.clj:19)\nat riemann.core$stream_BANG_.invoke(core.clj:15)\nat riemann.transport$handle.invokeStatic(transport.clj:171)\nat riemann.transport$handle.invoke(transport.clj:165)\nat riemann.transport.tcp$tcp_handler.invokeStatic(tcp.clj:109)\nat riemann.transport.tcp$tcp_handler.invoke(tcp.clj:102)\nat riemann.transport.tcp$gen_tcp_handler$fn__12899.invoke(tcp.clj:68)\nat riemann.transport.tcp.proxy$io.netty.channel.ChannelInboundHandlerAdapter$ff19274a.channelRead(Unknown Source)\nat io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:362)\nat io.netty.channel.AbstractChannelHandlerContext.access$600(AbstractChannelHandlerContext.java:38)\nat io.netty.channel.AbstractChannelHandlerContext$7.run(AbstractChannelHandlerContext.java:353)\nat io.netty.util.concurrent.DefaultEventExecutor.run(DefaultEventExecutor.java:66)\nat io.netty.util.concurrent.SingleThreadEventExecutor$5.run(SingleThreadEventExecutor.java:886)\nat io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)\nat java.lang.Thread.run(Thread.java:748)",
      "exception_class": "java.lang.NullPointerException",
      "exception_message": null
    },
    "file": "NO_SOURCE_FILE",
    "method": "invoke",
    "level": "WARN",
    "line_number": "0",
    "loggerName": "riemann.streams",
    "class": "clojure.tools.logging$eval257$fn__262",
    "mdc": {},
    "threadName": "defaultEventExecutorGroup-2-3"
  }
}
```
