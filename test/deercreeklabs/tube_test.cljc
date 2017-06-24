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

(def port 8080)

(defn <send-ws-msg-and-return-rsp [msg]
  (u/go-sf
   (let [url (str "ws://localhost:" port)
         client-rcv-chan (async/chan)
         connected-chan (async/chan)
         client-options {:on-connect (fn [ws]
                                       (async/put! connected-chan true))
                         :on-rcv (fn [ws data]
                                   (async/put! client-rcv-chan data))}
         client (tube-client/make-websocket url client-options)
         ;; wait for connection
         connected? (async/<! connected-chan)
         _ (u/send client msg)
         ret (async/<! client-rcv-chan)]
     (u/disconnect client)
     ret)))

(defmacro check-reversed [client-ret-ch msg timeout]
  `(let [timeout-ch# (async/timeout ~timeout)
         [[status# ret#] ch#] (async/alts! [~client-ret-ch timeout-ch#])]
     (cond
       (= timeout-ch# ch#)
       (is (= nil "Timed out waiting for client response..."))


       (= :failure status#)
       (is (= nil (str "Round trip failed: " ret#)))

       :else
       (is (u/equivalent-byte-arrays? ~msg (u/reverse-byte-array ret#))))))

(deftest test-round-trip-w-small-msg
  (u/test-async
   1000
   (u/go-sf
    (let [stop-server (tube-server/run-reverser-server port)]
      (try
        (let [msg (u/byte-array [72,101,108,108,111,32,119,111,114,108,100,33])
              client-ret-ch (<send-ws-msg-and-return-rsp msg)]
          (check-reversed client-ret-ch msg 1000))
        (finally
          (stop-server)))))))

#?(:clj  ;; File ops are only defined for clj
   (deftest test-round-trip-w-large-msg
     (u/test-async
      10000
      (u/go-sf
       (let [stop-server (tube-server/run-reverser-server port)]
         (try
           (let [msg (u/read-byte-array-from-file "lots_o_bytes.bin")
                 client-ret-ch (<send-ws-msg-and-return-rsp msg)]
             (check-reversed client-ret-ch msg 10000))
           (finally
             (stop-server))))))))
