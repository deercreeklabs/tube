(ns deercreeklabs.test-runner
  (:require
   [cljs.nodejs :as nodejs]
   [cljs.test :as test :refer-macros [run-tests]]
   [deercreeklabs.tube-test]))

(nodejs/enable-util-print!)

(defn -main [& args]
  (run-tests 'deercreeklabs.tube-test))

(set! *main-cli-fn* -main)
