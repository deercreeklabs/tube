(ns deercreeklabs.tube-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.server :as tube-server]
   [deercreeklabs.tube.utils :as u]
   [schema.core :as s :include-macros true]
   [schema.test :as st]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(use-fixtures :once schema.test/validate-schemas)

(timbre/set-level! :debug)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

(deftest test-round-trip
  (let [port 8080
        routes {"" (tube-server/make-ws-handler)}
        stop-server (tube-server/serve port routes)]
    (try
      (let [url (str "ws://localhost:" port)
            client-rcv-chan (async/chan)
            client-options {:on-rcv (fn [ws data]
                                      (debugf "Got data: %s" data)
                                      (async/put! client-rcv-chan data))}
            client-ws (tube-client/make-websocket url client-options)
            msg (.getBytes "Hello world" "UTF-8")
            _ (u/send client-ws msg)
            rsp (async/<!! client-rcv-chan)]
        (is (u/equivalent-byte-arrays? msg (u/reverse-byte-array rsp)))
        (u/disconnect client-ws))
      (finally
        (stop-server)))))
