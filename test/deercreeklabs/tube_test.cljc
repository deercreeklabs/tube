(ns deercreeklabs.tube-test
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as async
    :refer [alts! #?@(:clj [go])]]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.jetty :as jetty]
   [deercreeklabs.tube.utils :as u :refer [#?@(:clj [go-sf])]]
   [schema.core :as s :include-macros true]
   [schema.test :as st]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:cljs
     (:require-macros
      [cljs.core.async.macros :refer [go]]
      [deercreeklabs.tube.utils :refer [go-sf]])
     :clj
     (:import
        (org.eclipse.jetty.server Server))))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
;;(s/set-fn-validation! true)

(u/configure-logging)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ lein run

(def port 8080)

(defn <send-ws-msg-and-return-rsp [msg timeout]
  (u/go-sf
   (let [url (str "wss://localhost:" port)
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
   5000
   (go-sf
    (let [msg (u/byte-array [72,101,108,108,111,32,119,111,114,108,100,33])
          rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 1000000)]
      (is (u/equivalent-byte-arrays? msg (u/reverse-byte-array rsp)))))))

#_
#?(:clj  ;; File ops are currently only defined for clj
   (deftest test-round-trip-w-large-msg
     (u/test-async
      10000
      (go-sf
       (let [msg (u/read-byte-array-from-file "lots_o_bytes.bin")
             rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 10000)
             rev (u/reverse-byte-array rsp)
             m100 (u/slice-byte-array msg 0 100)
             r100 (u/slice-byte-array rev 0 100)
             msg-size (count msg)
             rsp-size (count rsp)]
         (is (= msg-size rsp-size))
         (is (u/equivalent-byte-arrays? m100 r100)))))))
#_
(deftest test-encode-decode
  (let [data [[0 [0]]
              [-1 [1]]
              [1 [2]]
              [100 [-56 1]]
              [-100 [-57 1]]
              [1000 [-48 15]]
              [10000 [-96 -100 1]]]]
    (doseq [[num expected-bs] data]
      (let [expected-ba (u/byte-array expected-bs)
            ba (u/int->zig-zag-encoded-byte-array num)
            [decoded rest] (u/read-zig-zag-encoded-int ba)]
        (is (u/equivalent-byte-arrays? expected-ba ba))
        (is (= num decoded))
        (is (nil? rest))))))
#_
(deftest test-wss-server
  (u/configure-logging)
  (let [keystore-path "/Users/chad/Desktop/keystore.jks"
        keystore-password "password"
        on-connect (fn [id path remote-addr]
                     (debugf "Got conn %s to %s on %s" id remote-addr path))
        on-disconnect (constantly :disconnected)
        on-rcv (constantly nil)
        server (jetty/serve 8000 keystore-path keystore-password
                            on-connect on-disconnect on-rcv)]
    (is (= nil server))
    (Thread/sleep (* 1000 100))
    (infof "Stopping server...")
    (.stop ^Server server)))
