(ns deercreeklabs.doo-test-runner
  (:require
   [doo.runner :refer-macros [doo-tests]]
   [deercreeklabs.tube-test]))

(enable-console-print!)

(doo-tests 'deercreeklabs.tube-test)
