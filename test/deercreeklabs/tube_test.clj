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

(u/configure-logging)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

(def lots-o-bytes (u/byte-array (take 5e5 (repeatedly #(rand-int 127)))))

(deftest test-round-trip
  (u/test-async
   1000000
   (u/go-sf
    (let [port 8080
          routes {"" (tube-server/make-ws-handler)}
          stop-server (tube-server/serve port routes)]
      (try
        (let [url (str "ws://localhost:" port)
              client-rcv-chan (async/chan)
              connected-chan (async/chan)
              client-options {:on-connect (fn [ws]
                                            (async/put! connected-chan true))
                              :on-rcv (fn [ws data]
                                        (async/put! client-rcv-chan data))}
              client-ws (tube-client/make-websocket url client-options)
              msg "Hello world!"
              msg-bin (.getBytes msg "UTF-8")
              msg-bin lots-o-bytes
              connected? (async/<! connected-chan) ;; wait for connection
              _ (u/send client-ws msg-bin)
              [rsp ch] (async/alts! [client-rcv-chan (async/timeout 1000000)])]
          (debugf "Got rsp")
          (if (= client-rcv-chan ch)
            (is (u/equivalent-byte-arrays? msg-bin (u/reverse-byte-array rsp)))
            (is (= nil "Timed out waiting for client response...")))
          (debugf "About to disconnect")
          (u/disconnect client-ws))
        (finally
          (stop-server)))))))
