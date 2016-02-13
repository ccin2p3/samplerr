# samplerr

* is a [riemann](http://riemann.io/) plugin
* writes your metrics to [elasticsearch](http://elastic.co/products/elasticsearch)
* aggregates data using arbitrary clojure functions
* aggregates data in realtime into different round robin time-based elasticsearch indices
* manages your time based elasticsearch indices and evicts old data
* ensures your metric stays within storage boundaries

## what's before alpha?

**DISCLAIMER**

This is an early alpha version. It only partially works right now

## Installation

After cloning the repo, you can build the plugin using [leiningen](/technomancy/leiningen)

```
lein uberjar
```

This will create a plugin jar named `samplerr-x.y.z-SNAPSHOT-standalone.jar` which you can include into your *cjava lasspath*, *e.g.*:

```
java -cp /usr/lib/riemann/riemann.jar:/usr/lib/riemann/samplerr-0.1.1-SNAPSHOT-standalone-up.jar riemann.bin start /etc/riemann/riemann.config
```

On debian or redhat you could also add the classpath using the `EXTRA_CLASSPATH` variable available respectively in `/etc/default/riemann` or `/etc/sysconfig/riemann`.

## Synopsis

```clojure
(load-plugins)

(let [day    "'samplerr-'YYYY.MM.DD"
      month  "'samplerr-'YYYY.MM"
      year   "'sampler-'YYYY"
      cfunc  [{:func folds/mean :name avg}
              {:func folds/minimum :name min}
              {:func folds/maximum :name max}]
      elastic (samplerr/connect "http://localhost:9200")
      rrdtool2016 (samplerr/archive 
                    { :elastic (samplerr/connect "http://127.0.0.1:9200")
                    	:rra     [{:step 20   :keep 86400     :es_index day   :cfunc cfunc}
                                {:step 600  :keep 5356800   :es_index month :cfunc cfunc}
                                {:step 3600 :keep 315567360 :es_index year  :cfunc cfunc}]
                      :expire-every 172800})]
  (streams
    (where (tagged "collectd")
       rrdtool2016)))
```

This will index all events tagged `collectd`, one document per `host`,`service`, `step`, and `cfunc`.

## Round Robin Archives

samplerr will send data to different elasticsearch time-based indices using different resolutions. The `:rra` parameter to `samplerr/index` contains a list of round-robin archives and should contain a vector of hash-maps with the following keys:

* `:step`: time in seconds during which riemann shall aggregate data
* `:keep`: time in seconds during which data should be kept at this resolution. Indices will be purged and linked to the next lower resolution index every `:expire-every` seconds
* `:cfunc`: consolidation stream function which should be used to aggregate data during `:step` interval. Examples: `folds/sum`, `folds/count`, *etc.*
* `:es_index`: elasticsearch index pattern the archive's stream shall be indexed to: should be a clj-time.format/formatter string
* `:es_type `: elasticsearch index type. defaults to `samplerr`

## Expiry

**UNIMPLEMENTED**

In order to keep disk space in bounds, samplerr will purge expiring high resolution data in favour of lower resolution data. To achieve this, it will periodically (every `:expire-every` seconds):

* process indices from highest to lowest resolution
* delete indices if their age is greater than `:keep`
* create an alias for the deleted index pointing to the overlapping, lower resolution corresponding index

### Example

Take the example in [#synopsis]. Let's say today is 2016-02-01.
Riemann started exactly 2 days ago. samplerr's reaper fires up and processes the elasticsearch indices:

* `samplerr-2016.02.01` is younger than two days, and thus left alone
* `samplerr-2016.01.31` is younger than two days, and thus left alone
* `samplerr-2016.01.30` is two days old and DELETED
* the alias `samplerr-2016.01.30` is created and pointed to `samplerr-2016.01`
* all other daily based indices are already aliases and thus left alone
* `sampler-2015.02` is younger than two months, and thus left alone
* `sampler-2015.01` is younger than two months, and thus left alone
* `sampler-2014.12` is two months old and DELETED
* the alias `sampler-2014.12` is created and pointed to `sampler-2014`
* …

