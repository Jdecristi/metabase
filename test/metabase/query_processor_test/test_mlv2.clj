(ns metabase.query-processor-test.test-mlv2
  (:require
   [clojure.string :as str]
   [clojure.test :as t :refer :all]
   [malli.core :as mc]
   [malli.error :as me]
   [metabase.lib.convert :as lib.convert]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.metadata.jvm :as lib.metadata.jvm]
   [metabase.lib.schema :as lib.schema]
   [metabase.mbql.util :as mbql.u]
   [metabase.util :as u]))

(set! *warn-on-reflection* true)

(defn- do-with-legacy-query-testing-context [query thunk]
  (testing (format "\nlegacy query =\n%s\n" (u/pprint-to-str query))
    (thunk)))

(defn- do-with-pMBQL-query-testing-context [pMBQL thunk]
  (testing (format "\npMBQL =\n%s\n" (u/pprint-to-str pMBQL))
    (thunk)))

(def ^:dynamic *skip-conversion-tests*
  "Whether we should skip the => pMBQL conversion tests, for queries that we explicitly expect to fail conversion
  because they are intentionally broken. For ones that are unintentionally broken, write a rule instead.

  At the time of this writing, this is only used in one
  place, [[metabase.models.query.permissions-test/invalid-queries-test]]."
  false)

(defn- skip-conversion-tests?
  "Whether to skip conversion tests against a `legacy-query`."
  [legacy-query]
  (or
   *skip-conversion-tests*
   ;; #29898: `:joins` with `:fields` other than `:all` or `:none` are not normalized correctly.
   (mbql.u/match-one legacy-query
     {:joins joins}
     (mbql.u/match-one joins
       {:fields fields}
       (mbql.u/match-one fields
         :field
         "#29898")))
   ;; #29897: `:datetime-diff` is not handled correctly.
   (mbql.u/match-one legacy-query
     :datetime-diff
     "#29897")
   ;; #29904: `:fields` in `:joins` are supposed to be returned even if `:fields` is specified.
   (mbql.u/match-one legacy-query
     {:fields fields, :joins joins}
     (mbql.u/match-one joins
       {:fields (join-fields :guard (partial not= :none))}
       "#29904"))
   ;; #29895: `:value` is not supported
   (mbql.u/match-one legacy-query
     :value
     "#29895")
   ;; #29908: native queries do not round trip correctly
   (when (:native legacy-query)
     "#29908")
   ;; #29909: these clauses are not implemented yet.
   (mbql.u/match-one legacy-query
     #{:get-year :get-quarter :get-month :get-day :get-day-of-week :get-hour :get-minute :get-second}
     "#29909")
   ;; #29938: conversion for `:case` with default value does not work correctly
   (mbql.u/match-one legacy-query
     :case
     (mbql.u/match-one &match
       {:default _default}
       "#29938"))
   ;; #29942: missing schema for `:cum-sum` and `:cum-count` aggregations
   (mbql.u/match-one legacy-query
     #{:cum-sum :cum-count}
     "#29942")
   ;; #29946: nested arithmetic expressions wrapping a `:field` clause
   (mbql.u/match-one legacy-query
     #{:+ :- :*}
     (mbql.u/match-one &match
       #{:+ :- :*}
       (mbql.u/match-one &match
         :field
         "#29946")))
   ;; #29948: `:substring` is broken
   (mbql.u/match-one legacy-query
     :substring
     "#29948")
   ;; #29949: missing schema
   (mbql.u/match-one legacy-query
     :regex-match-first
     "#29949")
   ;; #29950: string filter clauses with options like `:case-sensitive` option not handled correctly
   (mbql.u/match-one legacy-query
     {:case-sensitive _case-sensitive?}
     "#29950")
   ;; #29953: `:aggregation` and `:expression` refs with `nil` options
   (mbql.u/match-one legacy-query
     [:aggregation _index nil] "#29953"
     [:expression _name nil]   "#29953")
   ;; #29958: `:convert-timezone` with 2 args is broken
   (mbql.u/match-one legacy-query
     [:convert-timezone _expr _source-timezone]
     "#29958")))

(defn- skip-metadata-calculation-tests? [legacy-query]
  (or
   ;; #29907: wrong column name for joined columns in `:breakout`
   (mbql.u/match-one legacy-query
     {:breakout breakouts}
     (mbql.u/match-one breakouts
       [:field _id-or-name {:join-alias _join-alias}]
       "#29907"))
   ;; #29910: `:datetime-add`, `:datetime-subtract`, and `:convert-timezone` broken with string literals
   (mbql.u/match-one legacy-query
     #{:datetime-add :datetime-subtract :convert-timezone}
     (mbql.u/match-one &match
       [_tag (_literal :guard string?) & _]
       "#29910"))
   ;; #29935: metadata for an `:aggregation` with a `:case` expression not working
   (mbql.u/match-one legacy-query
     {:aggregation aggregations}
     (mbql.u/match-one aggregations
       :case
       "#29935"))
   ;; #29936: metadata for an `:aggregation` that is a `:metric`
   (mbql.u/match-one legacy-query
     {:aggregation aggregations}
     (mbql.u/match-one aggregations
       :metric
       "#28689"))
   ;; #29941 : metadata resolution for query with a `card__` source-table does not work correctly for `:field` <name>
   ;; #clauses
   (mbql.u/match-one legacy-query
     {:source-table (_id :guard #(str/starts-with? % "card__"))}
     (mbql.u/match-one &match
       [:field (_field-name :guard string?) _opts]
       "#29941"))
   ;; #29947: `:ends-with` broken
   (mbql.u/match-one legacy-query
     :ends-with
     "#29947")
   ;; #29964: `:percentile` column name calculation broken
   (mbql.u/match-one legacy-query
     :percentile
     "#29964")))

(defn- test-mlv2-metadata [original-query _qp-metadata]
  {:pre [(map? original-query)]}
  (when-not (or (skip-conversion-tests? original-query)
                (skip-metadata-calculation-tests? original-query))
    (do-with-legacy-query-testing-context
     original-query
     (^:once fn* []
      (let [pMBQL (-> original-query lib.convert/->pMBQL)]
        ;; don't bother doing this test if the output is invalid; [[test-mlv2-conversion]] will fail anyway, no point in
        ;; triggering an Exception here as well.
        (when (mc/validate ::lib.schema/query pMBQL)
          (do-with-pMBQL-query-testing-context
           pMBQL
           (^:once fn* []
            (let [metadata-provider (lib.metadata.jvm/application-database-metadata-provider (:database original-query))
                  mlv2-query        (lib/query metadata-provider pMBQL)
                  mlv2-metadata     (lib.metadata.calculation/metadata mlv2-query)]
              ;; Just make sure we can calculate some metadata (any metadata, even nothing) without throwing an
              ;; Exception at this point; making sure it is CORRECT will be the next step after this.
              (is (any? mlv2-metadata)))))))))))

(defn- test-mlv2-conversion [query]
  (when-not (skip-conversion-tests? query)
    (do-with-legacy-query-testing-context
     query
     (^:once fn* []
      (let [pMBQL (-> query lib.convert/->pMBQL)]
        (do-with-pMBQL-query-testing-context
         pMBQL
         (^:once fn* []
          (testing "Legacy MBQL queries should round trip to pMBQL and back"
            (is (= query
                   (-> pMBQL lib.convert/->legacy-MBQL))))
          (testing "converted pMBQL query should validate against the pMBQL schema"
            (is (not (me/humanize (mc/explain ::lib.schema/query pMBQL))))))))))))

(def ^:private ^:dynamic *original-query* nil)

(defn post-processing-middleware
  [_preprocessed-query rff]
  (fn [metadata]
    {:pre [(map? *original-query*)]}
    (test-mlv2-metadata *original-query* metadata)
    (rff metadata)))

(defn around-middleware
  "Tests only: save the original legacy MBQL query immediately after normalization to `::original-query`."
  [qp]
  (fn [query rff context]
    ;; there seems to be a issue in Hawk JUnit output if it encounters a test assertion when [[t/*testing-vars*]] is
    ;; empty, which can be the case if the assertion happens inside of a fixture before a test is ran (e.g. queries ran
    ;; as the result of syncing a database happening inside a test fixture); in this case we still want to run our
    ;; tests, so create some fake test var context so it doesn't fail.
    (if (empty? t/*testing-vars*)
      (binding [t/*testing-vars* [#'test-mlv2-conversion]]
        (test-mlv2-conversion query))
      (test-mlv2-conversion query))
    (binding [*original-query* query]
      (qp query rff context))))
