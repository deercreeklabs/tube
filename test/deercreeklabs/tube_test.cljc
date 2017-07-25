(ns deercreeklabs.tube-test
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as async
    :refer [alts! #?@(:clj [go])]]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.utils :as u :refer [#?@(:clj [go-sf])]]
   [schema.core :as s :include-macros true]
   [schema.test :as st]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:cljs
     (:require-macros
      [cljs.core.async.macros :refer [go]]
      [deercreeklabs.tube.utils :refer [go-sf]])))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
;;(s/set-fn-validation! true)

(u/configure-logging)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ lein run

(def port 8080)

(defn <send-ws-msg-and-return-rsp [msg timeout]
  (u/go-sf
   (let [url (str "ws://localhost:" port)
         client-rcv-ch (async/chan)
         connected-ch (async/chan)
         client-options {:on-connect (fn [ws]
                                       (async/put! connected-ch true))
                         :on-disconnect (fn [ws reason])
                         :on-rcv (fn [ws data]
                                   (async/put! client-rcv-ch data))}
         client (tube-client/make-websocket url client-options)
         ;; wait for connection
         connected? (async/<! connected-ch)
         _ (u/send client msg)
         timeout-ch (async/timeout timeout)
         [ret ch] (alts! [client-rcv-ch timeout-ch])]
     (u/disconnect client)
     (if (= timeout-ch ch)
       (throw (ex-info "Timed out waiting for client response"
                       {:type :execution-error
                        :subtype :timeout
                        :timeout timeout}))
       ret))))

(deftest test-round-trip-w-small-msg
  (u/test-async
   1000
   (go-sf
    (let [msg (u/byte-array [72,101,108,108,111,32,119,111,114,108,100,33])
          rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 1000000)]
      (is (u/equivalent-byte-arrays? msg (u/reverse-byte-array rsp)))))))

#?(:clj  ;; File ops are currently only defined for clj
   (deftest test-round-trip-w-large-msg
     (u/test-async
      10000
      (go-sf
       (let [msg (u/read-byte-array-from-file "lots_o_bytes.bin")
             rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 10000)]
         (is (u/equivalent-byte-arrays? msg (u/reverse-byte-array rsp))))))))
