(ns metabase.lib.card-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.test-metadata :as meta]
   [metabase.lib.test-util :as lib.tu]
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal]))))

(comment lib/keep-me)

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(deftest ^:parallel source-card-infer-metadata-test
  (testing "We should be able to calculate metadata for a Saved Question missing results_metadata"
    (let [query (lib.tu/query-with-card-source-table)]
      (is (=? [{:id                       (meta/id :checkins :user-id)
                :name                     "USER_ID"
                :lib/source               :source/card
                :lib/source-column-alias  "USER_ID"
                :lib/desired-column-alias "USER_ID"}
               {:name                     "count"
                :lib/source               :source/card
                :lib/source-column-alias  "count"
                :lib/desired-column-alias "count"}]
              (lib.metadata.calculation/metadata query)))
      (testing `lib/display-info
        (is (=? [{:name                   "USER_ID"
                  :display_name           "User ID"
                  :table                  {:name         "My Card"
                                           :display_name "My Card"}
                  :effective_type         :type/Integer
                  :semantic_type          :type/FK
                  :is_calculated          false
                  :is_from_previous_stage false
                  :is_implicitly_joinable false
                  :is_from_join           false}
                 {:name                   "count"
                  :display_name           "Count"
                  :table                  {:name         "My Card"
                                           :display_name "My Card"}
                  :effective_type         :type/Integer
                  :is_from_previous_stage false
                  :is_from_join           false
                  :is_calculated          false
                  :is_implicitly_joinable false}]
                (for [col (lib.metadata.calculation/metadata query)]
                  (lib/display-info query col))))))))
