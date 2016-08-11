(ns spec-provider.provider
  (:require [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.spec.test]
            [spec-provider.stats :as st]
            [spec-provider.merge :refer [merge-stats]]
            [clojure.walk :as walk]
            [clojure.pprint :refer [pprint]]))

;;this means that if the count of the distinct values is less than 10%
;;of the count of total values, then the attribute is considered an
;;enumeration
(def enum-threshold 0.1)

(def pred->form
  {string?     'string?
   double?     'double?
   float?      'float?
   integer?    'integer?
   keyword?    'keyword?
   boolean?    'boolean?
   sequential? 'coll-of?
   set?        'set?
   map?        'map?})

(def pred->name
  {string?     :string
   double?     :double
   float?      :float
   integer?    :integer
   keyword?    :keyword
   boolean?    :boolean
   sequential? :seq
   set?        :set
   map?        :map})

(defn summarize-leaf [{::st/keys [pred-map sample-count distinct-values hit-distinct-values-limit] :as stats}]
  (cond (and
         (not hit-distinct-values-limit)
         (>= enum-threshold
             (/ (float (count distinct-values))
                (float sample-count))))
        distinct-values

        (= 1 (count pred-map))
        (pred->form (ffirst pred-map))

        (> (count pred-map) 1)
        (concat
         (list 'clojure.spec/or)
         (mapcat (juxt pred->name pred->form) (map first pred-map)))))

(defn- qualified-key? [k] (some? (namespace k)))
(defn- qualify-key [k ns] (keyword (str ns) (name k)))

(defn summarize-keys [keys-stats ns]
  (let [highest-freq (apply max (map ::st/sample-count (vals keys-stats)))
        extract-keys (fn [filter-fn]
                       (->> keys-stats
                            (filter filter-fn)
                            (mapv #(qualify-key (key %) ns))
                            not-empty))
        req          (extract-keys
                      (fn [[k v]] (and (qualified-key? k) (= (::st/sample-count v) highest-freq))))
        opt          (extract-keys
                      (fn [[k v]] (and (qualified-key? k) (< (::st/sample-count v) highest-freq))))
        req-un       (extract-keys
                      (fn [[k v]] (and (not (qualified-key? k)) (= (::st/sample-count v) highest-freq))))
        opt-un       (extract-keys
                      (fn [[k v]] (and (not (qualified-key? k)) (< (::st/sample-count v) highest-freq))))]
    (cond-> (list 'clojure.spec/keys)
      req (concat [:req req])
      opt (concat [:opt opt])
      req-un (concat [:req-un req-un])
      opt-un (concat [:opt-un opt-un]))))

(defn- summarize-coll-elements [stats]
  (list `s/coll-of (summarize-leaf stats)))

(defn- summarize-pos-elements [stats]
  (concat
   (list `s/cat)
   (interleave
    (map #(keyword (str "el" %)) (range))
    (map summarize-leaf (vals (sort-by key stats)))))) ;;TODO extra rules for optional elements

(defn- summarize-or [stats]
  (concat (list `s/or)
          (interleave
           (map (fn [[pred _]] (pred->name pred)) stats)
           (map (fn [[pred stats]] (summarize-leaf pred stats)) stats))))

(defn summarize-stats* [{pred-map            ::st/pred-map
                         keys-stats          ::st/keys
                         elements-coll-stats ::st/elements-coll
                         elements-pos-stats  ::st/elements-pos
                         :as                 stats}
                        spec-name
                        spec-ns]
  (list `s/def spec-name
        (cond (and keys-stats elements-coll-stats)
              (list `s/or
                    :map
                    (summarize-keys keys-stats spec-ns)
                    :collection
                    (summarize-coll-elements elements-coll-stats))

              keys-stats
              (summarize-keys keys-stats spec-ns)

              elements-coll-stats
              (summarize-coll-elements elements-coll-stats)

              elements-pos-stats
              (summarize-pos-elements elements-pos-stats)

              :else
              (summarize-leaf stats))))

(defn summarize-stats [stats spec-name]
  (let [spec-ns    (namespace spec-name)
        {:keys [order stats]}
        (reduce (fn [flat [stat-name stats :as node]]
                  (if (::st/pred-map stats)
                    (-> flat
                        (update :order #(cons stat-name %))
                        ;;TODO warn on "incompatible" merge
                        (update-in [:stats stat-name] #(merge-stats % stats)))
                    flat))
                {:order ()
                 :stats {}}
                (tree-seq (comp ::st/keys second)
                          (comp ::st/keys second)
                          [spec-name stats]))]
    (map (fn [[stat-name stats]]
           (summarize-stats* stats (keyword spec-ns (name stat-name)) spec-ns))
         (map #(vector % (get stats %)) (distinct order)))))

(defn infer-specs [data spec-name]
  (when-not (namespace spec-name)
    (throw
     (ex-info (format "invalid spec-name %s - should be fully-qualified keyword" (str spec-name))
              {:spec-name spec-name})))
  (summarize-stats (reduce st/update-stats {} data) spec-name))

(defn unqualify-spec [spec domain-ns clojure-spec-ns]
  (let [domain-ns (str domain-ns)
        clojure-spec-ns (str clojure-spec-ns)]
    (walk/postwalk
     (fn [x]
       (cond (and (symbol? x) (= "clojure.spec" (namespace x))) (symbol clojure-spec-ns (name x))
             (and (keyword? x) (= domain-ns (namespace x))) (symbol (str "::" (name x))) ;;nasty hack to get the printer to print ::foo
             :else x))
     spec)))

(defn pprint-specs [specs domain-ns clojure-spec-ns]
  (doseq [spec (map #(unqualify-spec % domain-ns clojure-spec-ns) specs)]
    (pprint spec)))

;;infer-specs for nested maps algo:
;; 0. assign names to all nested maps based on the key
;;    they're under
;; 1. collect all nested maps stats. Also, root map (if map)
;; 2. collect all attribute stats from all maps
;; 3. (maybe) merge the attribute stats of the ones that have the same name
;; 4. infer spec for each attribute
;; 5. infer spec for each map keyset

;;(s/form (s/or :numeric (s/and integer? pos?) :string string?))
