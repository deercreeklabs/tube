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
   #?(:clj [org.httpkit.client :as http])
   [schema.core :as s :include-macros true]
   [schema.test :as st]))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
(s/set-fn-validation! true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Unit tests

;;;; IMPORTANT!!! You must start a server for these tests to work.
;;;; e.g. $ lein run

(def normal-uri "ws://localhost:8080/ws")
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
     :cljs (ba/concat-byte-arrays (take 10 (repeat tbs/test-bytes)))))

(deftest test-round-trip-w-small-msg
  (au/test-async
   30000
   (au/go
     (let [msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           norm-rsp (au/<? (<send-ws-msg-and-return-rsp normal-uri msg 25000))
           ;;ssl-rsp (au/<? (<send-ws-msg-and-return-rsp ssl-uri msg 25000))
           ]
       (is (not= nil norm-rsp))
       #_(is (not= nil ssl-rsp))
       (when norm-rsp
         (is (ba/equivalent-byte-arrays? msg
                                         (ba/reverse-byte-array norm-rsp))))
       #_(when ssl-rsp
           (is (ba/equivalent-byte-arrays? msg
                                           (ba/reverse-byte-array ssl-rsp))))))))

(deftest test-round-trip-w-large-msg
  (au/test-async
   #?(:clj 30000
      :cljs 60000)
   (au/go
     (let [msg (get-lots-of-bytes)
           rsp (au/<? (<send-ws-msg-and-return-rsp normal-uri msg 60000))]
       (is (not= nil rsp))
       (when rsp
         (let [rev (ba/reverse-byte-array rsp)
               m100 (ba/slice-byte-array msg 0 100)
               r100 (ba/slice-byte-array rev 0 100)
               msg-size (count msg)
               rsp-size (count rsp)]
           (is (= msg-size rsp-size))
           (is (ba/equivalent-byte-arrays? m100 r100))))))))

(deftest test-on-disconnect
  (au/test-async
   30000
   (au/go
     (let [disconnect-ch (ca/promise-chan)
           on-disconnect (fn [conn code reason]
                           (ca/put! disconnect-ch true))
           msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           rsp (au/<? (<send-ws-msg-and-return-rsp
                       normal-uri msg 25000 on-disconnect))]
       (is (not= nil rsp))
       (when rsp
         (is (ba/equivalent-byte-arrays? msg (ba/reverse-byte-array rsp)))
         (let [[ret ch] (ca/alts! [disconnect-ch (ca/timeout 5000)])]
           (is (= disconnect-ch ch))
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
   2000
   (au/go
     (let [uri "ws://not-a-real-url.not-a-domain"
           client (au/<? (tube-client/<tube-client
                          uri 1000 {:log-conn-failure? false}))]
       (is (= nil client))))))

;; TODO: Make these work in cljs and w/ SSL
#?(:clj
   (deftest test-http-handler
     (let [ret @(http/get "http://localhost:8080")]
       (is (= "Yo" (:body ret))))))
