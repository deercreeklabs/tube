(ns deercreeklabs.tube-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.bytes :as tbs]
   [deercreeklabs.log-utils :as lu]
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.utils :as u]
   [schema.core :as s :include-macros true]
   [schema.test :as st]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
(s/set-fn-validation! true)

(u/configure-logging)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ lein run

(def port 8080)

(defn <send-ws-msg-and-return-rsp [msg timeout]
  (ca/go
    (let [uri (str "ws://localhost:" port)
          client-rcv-ch (ca/chan)
          options {:on-rcv (fn [conn data]
                             (ca/put! client-rcv-ch data))}
          client (au/<? (tube-client/<make-tube-client uri 1000 options))
          _ (is (not= nil client))
          _ (tube-client/send client msg)
          [ret ch] (ca/alts! [client-rcv-ch (ca/timeout timeout)])]
      (tube-client/close client)
      (if (= client-rcv-ch ch)
        ret
        (throw (ex-info "Timed out waiting for client response"
                        {:type :execution-error
                         :subtype :timeout
                         :timeout timeout}))))))

(defn get-lots-of-bytes []
  #?(:clj (ba/read-byte-array-from-file "lots_o_bytes.bin")
     :cljs (ba/concat-byte-arrays (take 10 (repeat tbs/test-bytes)))))

(deftest test-round-trip-w-small-msg
  (au/test-async
   30000
   (ca/go
     (let [msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           rsp (au/<? (<send-ws-msg-and-return-rsp msg 25000))]
       (is (ba/equivalent-byte-arrays? msg (ba/reverse-byte-array rsp)))))))

(deftest test-round-trip-w-large-msg
  (au/test-async
   #?(:clj 15000
      :cljs 60000)
   (ca/go
     (let [msg (get-lots-of-bytes)
           rsp (au/<? (<send-ws-msg-and-return-rsp msg 60000))
           rev (ba/reverse-byte-array rsp)
           m100 (ba/slice-byte-array msg 0 100)
           r100 (ba/slice-byte-array rev 0 100)
           msg-size (count msg)
           rsp-size (count rsp)]
       (is (= msg-size rsp-size))
       (is (ba/equivalent-byte-arrays? m100 r100))))))

(deftest test-encode-decode
  (let [data [[0 [0]]
              [-1 [1]]
              [1 [2]]
              [100 [-56 1]]
              [-100 [-57 1]]
              [1000 [-48 15]]
              [10000 [-96 -100 1]]]]
    (doseq [[num expected-bs] data]
      (let [expected-ba (ba/byte-array expected-bs)
            ba (ba/encode-int num)
            [decoded rest] (ba/decode-int ba)]
        (is (ba/equivalent-byte-arrays? expected-ba ba))
        (is (= num decoded))
        (is (nil? rest))))))

(deftest test-bad-uri
  (au/test-async
   2000
   (ca/go
     (let [uri "ws://not-a-real-url.not-a-domain"
           client (au/<? (tube-client/<make-tube-client
                          uri 1000 {:log-conn-failure? false}))]
       (is (= nil client))))))
