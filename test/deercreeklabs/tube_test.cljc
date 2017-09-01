(ns deercreeklabs.tube-test
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as async
    :refer [alts! #?@(:clj [go])]]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.bytes :as tbs]
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
(s/set-fn-validation! false)

(u/configure-logging)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ lein run

(def port 8080)

(defn <send-ws-msg-and-return-rsp [msg timeout]
  (u/go-sf
   (let [uri (str "wss://chadlaptop.f1shoppingcart.com:" port)
         client-rcv-ch (async/chan)
         options {:on-rcv (fn [conn data]
                            (async/put! client-rcv-ch data))}
         client (u/call-sf! tube-client/<make-tube-client uri options)
         _ (tube-client/send client msg)
         [ret ch] (alts! [client-rcv-ch (async/timeout timeout)])]
     (tube-client/close client)
     (if (= client-rcv-ch ch)
       ret
       (throw (ex-info "Timed out waiting for client response"
                       {:type :execution-error
                        :subtype :timeout
                        :timeout timeout}))))))


(defn get-lots-of-bytes []
  #?(:clj (u/read-byte-array-from-file "lots_o_bytes.bin")
     :cljs (u/concat-byte-arrays (take 10 (repeat tbs/test-bytes)))))

(deftest test-round-trip-w-small-msg
  (u/test-async
   5000
   (go-sf
    (let [msg (u/byte-array [72,101,108,108,111,32,119,111,114,108,100,33])
          rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 1000000)]
      (is (u/equivalent-byte-arrays? msg (u/reverse-byte-array rsp)))))))

(deftest test-round-trip-w-large-msg
  (u/test-async
   #?(:clj 15000
      :cljs 60000)
   (go-sf
    (let [msg (get-lots-of-bytes)
          rsp (u/call-sf! <send-ws-msg-and-return-rsp msg 60000)
          rev (u/reverse-byte-array rsp)
          m100 (u/slice-byte-array msg 0 100)
          r100 (u/slice-byte-array rev 0 100)
          msg-size (count msg)
          rsp-size (count rsp)]
      (is (= msg-size rsp-size))
      (is (u/equivalent-byte-arrays? m100 r100))))))

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
            ba (u/encode-int num)
            [decoded rest] (u/decode-int ba)]
        (is (u/equivalent-byte-arrays? expected-ba ba))
        (is (= num decoded))
        (is (nil? rest))))))
