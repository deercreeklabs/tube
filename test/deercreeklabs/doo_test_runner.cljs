(ns deercreeklabs.doo-test-runner
  (:require
   [doo.runner :refer-macros [doo-tests]]
   [deercreeklabs.tube-test]))


(doo-tests 'deercreeklabs.tube-test)
