(ns deercreeklabs.tube-test
  (:require
   #?(:cljs [cljs.test :as t])
   #?(:clj [clojure.test :refer [deftest is use-fixtures]])
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.server :as tube-server]
   [deercreeklabs.tube.utils :as u]
   [schema.core :as s :include-macros true]
   [schema.test :as st]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]])
  #?(:cljs
     (:require-macros
      [cljs.test :refer [deftest is use-fixtures]])))

(use-fixtures :once schema.test/validate-schemas)

(timbre/set-level! :debug)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

(defn server-on-connect
  [remote-addr sender closer]
  (let [on-rcv #(do
                  (debugf "Got: %s" %)
                  (sender %)) ;; Just echo what we get
        on-disconnect (fn [reason]
                        (debugf "Websocket (%s) disconnected. Reason: %s"
                                remote-addr reason))
        on-error #(do (debugf "Error: %s" %)
                        (on-disconnect %))]
    (u/sym-map on-rcv on-error on-disconnect)))



(deftest test-round-trip
  (let [port 8080
        routes {"/" tube-server/make-ws-handler server-on-connect}
        stop-server (tube-server/serve port routes)
        client (tube-client/make-client "localhost" port)]
    (stop-server)))
