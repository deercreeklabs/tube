(ns deercreeklabs.tube-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.bytes :as tbs]
   [deercreeklabs.tube.client :as tube-client]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [schema.core :as s :include-macros true]
   [schema.test :as st]))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
(s/set-fn-validation! true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ bin/run-test-server

(def normal-uri "ws://localhost:8080/ws")
(def foo-uri "ws://localhost:8080/foo")
(def ssl-uri "wss://localhost:8443/ws")

(defn <send-ws-msg-and-return-rsp
  ([uri msg timeout]
   (<send-ws-msg-and-return-rsp uri msg timeout (constantly nil)))
  ([uri msg timeout on-disconnect]
   (au/go
     (let [client-rcv-ch (ca/chan)
           options {:on-rcv (fn [conn data]
                              (ca/put! client-rcv-ch data))
                    :on-disconnect on-disconnect
                    :keep-alive-secs 25}
           client (au/<? (tube-client/<tube-client uri 1000 options))]
       (is (not= nil client))
       (when client
         (tube-client/send client msg)
         (let [[ret ch] (ca/alts! [client-rcv-ch (ca/timeout timeout)])]
           (tube-client/close client)
           (if (= client-rcv-ch ch)
             ret
             (throw (ex-info "Timed out waiting for client response"
                             {:type :execution-error
                              :subtype :timeout
                              :timeout timeout})))))))))

(defn get-lots-of-bytes []
  #?(:clj (ba/read-byte-array-from-file "test/lots_o_bytes.bin")
     ;; Using a larger payload than 1MB (100 * 10KB) causes problems
     ;; with kaocha's infrastructure
     :cljs (ba/concat-byte-arrays (take 900 (repeat tbs/test-bytes)))))

(deftest test-round-trip-w-small-msg
  (au/test-async
   30000
   (au/go
     (let [msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           norm-rsp (au/<? (<send-ws-msg-and-return-rsp normal-uri msg 25000))
           foo-rsp (au/<? (<send-ws-msg-and-return-rsp foo-uri msg 25000))
           ssl-rsp (au/<? (<send-ws-msg-and-return-rsp ssl-uri msg 25000))]
       (is (not= nil norm-rsp))
       (is (not= nil foo-rsp))
       (is (not= nil ssl-rsp))
       (when norm-rsp
         (is (ba/equivalent-byte-arrays? msg
                                         (ba/reverse-byte-array norm-rsp))))
       (when foo-rsp
         (is (ba/equivalent-byte-arrays? msg
                                         (ba/reverse-byte-array foo-rsp))))
       (when ssl-rsp
         (is (ba/equivalent-byte-arrays? msg
                                         (ba/reverse-byte-array foo-rsp))))))))

(defn first-100-bytes-eq? [ba0 ba1]
  (let [f100-0 (ba/slice-byte-array ba0 0 100)
        f100-1 (ba/slice-byte-array ba1 0 100)]
    (ba/equivalent-byte-arrays? f100-0 f100-1)))

;; This has trouble passing in the browser due to kaocha-cljs issues. Probably
;; can be fixed by moving to kaocha-cljs2
#?(:clj
   (deftest test-round-trip-w-large-msg
     (au/test-async
      #?(:clj  30000
         :cljs 60000)
      (au/go
        (let [msg (get-lots-of-bytes)
              rsp (au/<? (<send-ws-msg-and-return-rsp normal-uri msg 60000))
              ssl-rsp (au/<? (<send-ws-msg-and-return-rsp ssl-uri msg 60000))
              msg-size (count msg)
              reversed-msg (ba/reverse-byte-array msg)]
          (is (not= nil rsp))
          (is (not= nil ssl-rsp))
          (when rsp
            (is (= msg-size (count rsp)))
            (is (first-100-bytes-eq? rsp reversed-msg)))
          (when ssl-rsp
            (is (= msg-size (count ssl-rsp)))
            (is (first-100-bytes-eq? ssl-rsp reversed-msg))))))))

(deftest test-on-disconnect
  (au/test-async
   30000
   (au/go
     (let [disconnect-ch (ca/promise-chan)
           ssl-disconnect-ch (ca/promise-chan)
           on-disconnect (fn [conn code reason]
                           (ca/put! disconnect-ch true))
           ssl-on-disconnect (fn [conn code reason]
                               (ca/put! ssl-disconnect-ch true))
           msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           rsp (au/<? (<send-ws-msg-and-return-rsp normal-uri msg 25000
                                                   on-disconnect))
           ssl-rsp (au/<? (<send-ws-msg-and-return-rsp ssl-uri msg 25000
                                                       ssl-on-disconnect))]
       (is (not= nil rsp))
       (is (not= nil ssl-rsp))
       (when rsp
         (is (ba/equivalent-byte-arrays? msg (ba/reverse-byte-array rsp)))
         (let [[ret ch] (ca/alts! [disconnect-ch (ca/timeout 5000)])]
           (is (= disconnect-ch ch))
           (is (= true ret))))
       (when ssl-rsp
         (is (ba/equivalent-byte-arrays? msg (ba/reverse-byte-array ssl-rsp)))
         (let [[ret ch] (ca/alts! [ssl-disconnect-ch (ca/timeout 5000)])]
           (is (= ssl-disconnect-ch ch))
           (is (= true ret))))))))

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
   3000
   (au/go
     (let [uri "ws://not-a-real-url.not-a-domain"
           client (au/<? (tube-client/<tube-client
                          uri 1000 {:log-conn-failure? false}))]
       (is (= nil client))))))
