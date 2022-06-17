(ns riemann.plugin.samplerr-test
  (:require [clojure.test :refer :all]
            [riemann.plugin.samplerr :refer :all]
            ))

(deftest identify-index-date
  (let [index {:index "samplerr-2020"}]
    (is (= true                   (yearly-index? index)))
    (is (= false                  (monthly-index? index)))
    (is (= false                  (daily-index? index)))
    (is (= '("2020" nil nil)      (index-parts index)))
    (is (= 1                      (index-day index)))
    (is (= 1                      (index-month index)))
    (is (= 2020                   (index-year index)))
    (is (= "2020-01-01T00:00:00Z" (index-start-timestamp index))))
  (let [index {:index "samplerr-2020.04"}]
    (is (= false                  (yearly-index? index)))
    (is (= true                   (monthly-index? index)))
    (is (= false                  (daily-index? index)))
    (is (= '("2020" "04" nil)     (index-parts index)))
    (is (= 1                      (index-day index)))
    (is (= 4                      (index-month index)))
    (is (= 2020                   (index-year index)))
    (is (= "2020-04-01T00:00:00Z" (index-start-timestamp index))))
  (let [index {:index "samplerr-2020.04.22"}]
    (is (= false                  (yearly-index? index)))
    (is (= false                  (monthly-index? index)))
    (is (= true                   (daily-index? index)))
    (is (= '("2020" "04" "22")    (index-parts index)))
    (is (= 22                     (index-day index)))
    (is (= 4                      (index-month index)))
    (is (= 2020                   (index-year index)))
    (is (= "2020-04-22T00:00:00Z" (index-start-timestamp index)))))

(deftest test-remove-existing-aliases-query
  (let [aliases '({:index ".samplerr-2021"       :alias "samplerr-2021"}
                  {:index ".samplerr-2022"       :alias "samplerr-2022"}
                  {:index ".samplerr-2022.12"    :alias "samplerr-2022.12"}
                  {:index ".samplerr-2022.12.04" :alias "samplerr-2022.12.04"})]
    (is (= {:url "_aliases"
            :method :post
            :body {:actions '({:remove {:index ".samplerr-2021"       :alias "samplerr-2021"}}
                              {:remove {:index ".samplerr-2022"       :alias "samplerr-2022"}}
                              {:remove {:index ".samplerr-2022.12"    :alias "samplerr-2022.12"}}
                              {:remove {:index ".samplerr-2022.12.04" :alias "samplerr-2022.12.04"}})}}
           (remove-existing-aliases-query aliases)))))
(deftest test-create-aliases-query
  (let [indices '({:index ".samplerr-2021"}
                  {:index ".samplerr-2022"}
                  {:index ".samplerr-2022.10"}
                  {:index ".samplerr-2022.11"}
                  {:index ".samplerr-2022.12"}
                  {:index ".samplerr-2023"}
                  {:index ".samplerr-2023.01"}
                  {:index ".samplerr-2023.01.23"}
                  {:index ".samplerr-2023.01.24"}
                  {:index ".samplerr-2023.01.25"}
                  {:index ".samplerr-2023.01.26"}
                  {:index ".samplerr-2023.01.27"}
                  {:index ".samplerr-2023.01.28"}
                  {:index ".samplerr-2023.01.29"}
                  {:index ".samplerr-2023.01.30"}
                  {:index ".samplerr-2023.01.31"}
                  {:index ".samplerr-2023.02"}
                  {:index ".samplerr-2023.02.01"}
                  {:index ".samplerr-2023.02.02"}
                  {:index ".samplerr-2023.02.03"}
                  {:index ".samplerr-2023.02.04"})]
    (is (= {:url "_aliases"
            :method :post
            :body {:actions '({:add {:index ".samplerr-2021"       :alias "samplerr-2021"}}
                              {:add {:index ".samplerr-2022"       :alias "samplerr-2022"       :filter {:range {"@timestamp" {:lt "2022-10-01T00:00:00Z"}}}}}
                              {:add {:index ".samplerr-2022.10"    :alias "samplerr-2022.10"}}
                              {:add {:index ".samplerr-2022.11"    :alias "samplerr-2022.11"}}
                              {:add {:index ".samplerr-2022.12"    :alias "samplerr-2022.12"}}
                              {:add {:index ".samplerr-2023.01"    :alias "samplerr-2023.01"    :filter {:range {"@timestamp" {:lt "2023-01-23T00:00:00Z"}}}}}
                              {:add {:index ".samplerr-2023.01.23" :alias "samplerr-2023.01.23"}}
                              {:add {:index ".samplerr-2023.01.24" :alias "samplerr-2023.01.24"}}
                              {:add {:index ".samplerr-2023.01.25" :alias "samplerr-2023.01.25"}}
                              {:add {:index ".samplerr-2023.01.26" :alias "samplerr-2023.01.26"}}
                              {:add {:index ".samplerr-2023.01.27" :alias "samplerr-2023.01.27"}}
                              {:add {:index ".samplerr-2023.01.28" :alias "samplerr-2023.01.28"}}
                              {:add {:index ".samplerr-2023.01.29" :alias "samplerr-2023.01.29"}}
                              {:add {:index ".samplerr-2023.01.30" :alias "samplerr-2023.01.30"}}
                              {:add {:index ".samplerr-2023.01.31" :alias "samplerr-2023.01.31"}}
                              {:add {:index ".samplerr-2023.02.01" :alias "samplerr-2023.02.01"}}
                              {:add {:index ".samplerr-2023.02.02" :alias "samplerr-2023.02.02"}}
                              {:add {:index ".samplerr-2023.02.03" :alias "samplerr-2023.02.03"}}
                              {:add {:index ".samplerr-2023.02.04" :alias "samplerr-2023.02.04"}}
                              )}}
           (create-aliases-query indices ".samplerr-" "samplerr-")))))
