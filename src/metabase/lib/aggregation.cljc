(ns metabase.lib.aggregation
  (:refer-clojure :exclude [count distinct max min var])
  (:require
   [metabase.lib.common :as lib.common]
   [metabase.lib.hierarchy :as lib.hierarchy]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.schema :as lib.schema]
   [metabase.lib.schema.aggregation :as lib.schema.aggregation]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.lib.util :as lib.util]
   [metabase.shared.util.i18n :as i18n]
   [metabase.util.malli :as mu]))

(mu/defn column-metadata->aggregation-ref :- :mbql.clause/aggregation
  "Given `:metadata/field` column metadata for an aggregation, construct an `:aggregation` reference."
  [metadata :- lib.metadata/ColumnMetadata]
  (let [options {:lib/uuid       (str (random-uuid))
                 :effective-type ((some-fn :effective_type :base_type) metadata)}
        index   (::aggregation-index metadata)]
    (assert (integer? index) "Metadata for an aggregation reference should include ::aggregation-index")
    [:aggregation options index]))

(mu/defn resolve-aggregation :- ::lib.schema.aggregation/aggregation
  "Resolve an aggregation with a specific `index`."
  [query        :- ::lib.schema/query
   stage-number :- :int
   index        :- ::lib.schema.common/int-greater-than-or-equal-to-zero]
  (let [{aggregations :aggregation} (lib.util/query-stage query stage-number)]
    (when (<= (clojure.core/count aggregations) index)
      (throw (ex-info (i18n/tru "No aggregation at index {0}" index)
                      {:index        index
                       :query        query
                       :stage-number stage-number})))
    (nth aggregations index)))

(defmethod lib.metadata.calculation/describe-top-level-key-method :aggregation
  [query stage-number _k]
  (when-let [aggregations (not-empty (:aggregation (lib.util/query-stage query stage-number)))]
    (lib.util/join-strings-with-conjunction
     (i18n/tru "and")
     (for [aggregation aggregations]
       (lib.metadata.calculation/display-name query stage-number aggregation)))))

(defmethod lib.metadata.calculation/metadata-method :aggregation
  [query stage-number [_ag opts index, :as _aggregation-ref]]
  (let [aggregation (resolve-aggregation query stage-number index)]
    (merge
     (lib.metadata.calculation/metadata query stage-number aggregation)
     {:lib/source :source/aggregations}
     (when (:base-type opts)
       {:base_type (:base-type opts)})
     (when (:effective-type opts)
       {:effective_type opts}))))

;;; TODO -- merge this stuff into `defop` somehow.

(defmethod lib.metadata.calculation/display-name-method :aggregation
  [query stage-number [_tag _opts index]]
  (lib.metadata.calculation/display-name query stage-number (resolve-aggregation query stage-number index)))

(defmethod lib.metadata.calculation/display-name-method :count
  [query stage-number [_count _opts x]]
  ;; x is optional.
  (if x
    (i18n/tru "Count of {0}" (lib.metadata.calculation/display-name query stage-number x))
    (i18n/tru "Count")))

(defmethod lib.metadata.calculation/column-name-method :count
  [query stage-number [_count _opts x]]
  (if x
    (str "count_" (lib.metadata.calculation/column-name query stage-number x))
    "count"))

(lib.hierarchy/derive :count ::aggregation)

(defmethod lib.metadata.calculation/display-name-method :case
  [_query _stage-number _case]
  (i18n/tru "Case"))

(defmethod lib.metadata.calculation/column-name-method :case
  [_query _stage-number _case]
  "case")

(lib.hierarchy/derive :case ::aggregation)

(lib.hierarchy/derive ::unary-aggregation ::aggregation)

(doseq [tag [:avg
             :cum-count
             :cum-sum
             :distinct
             :max
             :median
             :min
             :stddev
             :sum
             :var]]
  (lib.hierarchy/derive tag ::unary-aggregation))

(defmethod lib.metadata.calculation/column-name-method ::unary-aggregation
  [query stage-number [tag _opts arg]]
  (let [arg (lib.metadata.calculation/column-name-method query stage-number arg)]
    (str
     (case tag
       :avg       "avg_"
       :cum-count "cum_count_"
       :cum-sum   "cum_sum_"
       :distinct  "distinct_"
       :max       "max_"
       :median    "median_"
       :min       "min_"
       :stddev    "std_dev_"
       :sum       "sum_"
       :var       "var_")
     arg)))

(defmethod lib.metadata.calculation/display-name-method ::unary-aggregation
  [query stage-number [tag _opts arg]]
  (let [arg (lib.metadata.calculation/display-name query stage-number arg)]
    (case tag
      :avg       (i18n/tru "Average of {0}"            arg)
      :cum-count (i18n/tru "Cumulative count of {0}"   arg)
      :cum-sum   (i18n/tru "Cumulative sum of {0}"     arg)
      :distinct  (i18n/tru "Distinct values of {0}"    arg)
      :max       (i18n/tru "Max of {0}"                arg)
      :median    (i18n/tru "Median of {0}"             arg)
      :min       (i18n/tru "Min of {0}"                arg)
      :stddev    (i18n/tru "Standard deviation of {0}" arg)
      :sum       (i18n/tru "Sum of {0}"                arg)
      :var       (i18n/tru "Variance of {0}"           arg))))

(defmethod lib.metadata.calculation/display-name-method :percentile
  [query stage-number [_percentile _opts x p]]
  (i18n/tru "{0}th percentile of {1}" p (lib.metadata.calculation/display-name query stage-number x)))

(defmethod lib.metadata.calculation/column-name-method :percentile
  [query stage-number [_percentile _opts x p]]
  (lib.util/format "p%d_%s" p (lib.metadata.calculation/column-name query stage-number x)))

(lib.hierarchy/derive :percentile ::aggregation)

;;; we don't currently have sophisticated logic for generating nice display names for filter clauses.
;;;
;;; TODO : wait a minute, we do have that stuff now!

(defmethod lib.metadata.calculation/display-name-method :sum-where
  [query stage-number [_sum-where _opts x _pred]]
  (i18n/tru "Sum of {0} matching condition" (lib.metadata.calculation/display-name query stage-number x)))

(defmethod lib.metadata.calculation/column-name-method :sum-where
  [query stage-number [_sum-where _opts x _pred]]
  (str "sum_where_" (lib.metadata.calculation/column-name query stage-number x)))

(lib.hierarchy/derive :sum-where ::aggregation)

(defmethod lib.metadata.calculation/display-name-method :share
  [_query _stage-number _share]
  (i18n/tru "Share of rows matching condition"))

(defmethod lib.metadata.calculation/column-name-method :share
  [_query _stage-number _share]
  "share")

(lib.hierarchy/derive :share ::aggregation)

(defmethod lib.metadata.calculation/display-name-method :count-where
  [_query _stage-number _count-where]
  (i18n/tru "Count of rows matching condition"))

(defmethod lib.metadata.calculation/column-name-method :count-where
  [_query _stage-number _count-where]
  "count-where")

(lib.hierarchy/derive :count-where ::aggregation)

(defmethod lib.metadata.calculation/metadata-method ::aggregation
  [query stage-number [_tag _opts first-arg :as clause]]
  (merge
   ;; flow the `:options` from the field we're aggregating. This is important, for some reason.
   ;; See [[metabase.query-processor-test.aggregation-test/field-settings-for-aggregate-fields-test]]
   (when first-arg
     (select-keys (lib.metadata.calculation/metadata query stage-number first-arg) [:settings]))
   ((get-method lib.metadata.calculation/metadata-method :default) query stage-number clause)))

(lib.common/defop count       [] [x])
(lib.common/defop avg         [x])
(lib.common/defop count-where [x y])
(lib.common/defop distinct    [x])
(lib.common/defop max         [x])
(lib.common/defop median      [x])
(lib.common/defop min         [x])
(lib.common/defop percentile  [x y])
(lib.common/defop share       [x])
(lib.common/defop stddev      [x])
(lib.common/defop sum         [x])
(lib.common/defop sum-where   [x y])
(lib.common/defop var         [x])

(mu/defn aggregate :- ::lib.schema/query
  "Adds an aggregation to query."
  ([query an-aggregate-clause]
   (aggregate query -1 an-aggregate-clause))
  ([query stage-number an-aggregate-clause]
   (let [stage-number (or stage-number -1)]
     (lib.util/update-query-stage
       query stage-number
       update :aggregation
       (fn [aggregations]
         (conj (vec aggregations) (lib.common/->op-arg query stage-number an-aggregate-clause)))))))

(mu/defn aggregations :- [:maybe [:sequential lib.metadata/ColumnMetadata]]
  "Get metadata about the aggregations in a given stage of a query."
  ([query]
   (aggregations query -1))

  ([query        :- ::lib.schema/query
    stage-number :- :int]
   (some->> (not-empty (:aggregation (lib.util/query-stage query stage-number)))
            (into [] (map-indexed (fn [i aggregation]
                                    (-> (lib.metadata.calculation/metadata query stage-number aggregation)
                                        (assoc :lib/source :source/aggregations, ::aggregation-index i))))))))
