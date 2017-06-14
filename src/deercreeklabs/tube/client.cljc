(ns deercreeklabs.tube.client
  (:require
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]]))


;;;;;;;;;;;;;;;;;;;; API ;;;;;;;;;;;;;;;;;;;;

(defn make-client [server-addr server-port]
  )
