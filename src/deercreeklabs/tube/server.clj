(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [aleph.http :as http]
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.nio HeapByteBuffer)))

(primitive-math/use-primitive-operators)

(defprotocol ITubeServer
  (start [this] "Start serving")
  (stop [this] "Stop serving")
  (get-conn-count [this] "Return the number of current connections"))

(deftype TubeServer [*conn-count starter *server]
  ITubeServer
  (start [this]
    (if @*server
      (infof "Server is already started.")
      (starter)))

  (stop [this]
    (if-let [^java.io.Closeable server @*server]
      (do
        (.close server)
        (reset! *server nil))
      (infof "Server is not running.")))

  (get-conn-count [this]
    @*conn-count))

(defn make-ws-handler
  [on-connect on-disconnect compression-type *conn-count *conn-id]
  (fn handle-ws [req socket]
    (try
      (let [{:keys [uri remote-addr]} req
            fragment-size 65000 ;; TODO: Figure out this size
            conn-id (swap! *conn-id #(inc (int %)))
            _ (swap! *conn-count #(inc (int %)))
            _ (debugf "Opened conn %s on %s from %s. Conn count: %d"
                      conn-id uri remote-addr @*conn-count)
            sender (fn [data]
                     (s/put! socket data))
            _ (debugf "1")
            closer #(s/close! socket)
            _ (debugf "2")
            conn (connection/make-connection
                  conn-id on-connect uri sender closer fragment-size
                  compression-type false)
            _ (debugf "3")
            on-close  (fn [reason]
                        (swap! *conn-count #(dec (int %)))
                        (debugf (str "Closed conn %s on %s from %s. "
                                     "Conn count: %s")
                                conn-id uri remote-addr @*conn-count)
                        (on-disconnect conn-id 1000 reason))
            _ (debugf "4")
            on-rcv (fn [data]
                     (debugf "@@@ server: Entering on-rcv. @@@")
                     (connection/handle-data conn data))]
        (debugf "5")
        (s/consume on-rcv socket)
        (debugf "6")
        (s/on-closed socket on-close)
        (debugf "7"))
      (catch Exception e
        (errorf "Unexpected exception in handle-ws")
        (lu/log-exception e)))))

(defn make-http-handler [<handle-http http-timeout-ms]
  (fn [req]
    (s/take!
     (s/->source
      (au/go
        (try
          (let [ret-ch (<handle-http req)
                timeout-ch (ca/timeout (or http-timeout-ms 1000))
                [ret ch] (au/alts? [ret-ch timeout-ch])]
            (cond
              (map? ret) ret
              (string? ret) {:status 200 :body ret}
              (= timeout-ch ch) {:status 504 :body ""}
              :else {:status 500 :body "Bad response"}))
          (catch Exception e
            (errorf "Unexpected exception in http-handler.")
            (lu/log-exception e))))))))

(defn <handle-http-test [req]
  (au/go
    (let [{:keys [body]} req]
      (clojure.string/upper-case (slurp body)))))

(defn make-tube-server
  ([port on-connect on-disconnect compression-type]
   (make-tube-server port on-connect on-disconnect compression-type {}))
  ([port on-connect on-disconnect compression-type opts]
   (let [{:keys [<handle-http
                 http-timeout-ms]} opts
         *conn-count (atom 0)
         *server (atom nil)
         *conn-id (atom 0)
         ws-handler (make-ws-handler on-connect on-disconnect compression-type
                                     *conn-count *conn-id)
         http-handler (if <handle-http
                        (make-http-handler <handle-http http-timeout-ms)
                        (make-http-handler <handle-http-test 1000))
         handler (fn [req]
                   (-> (d/let-flow [socket (http/websocket-connection req)]
                         (ws-handler req socket))
                       (d/catch ;; non-websocket request
                           (fn [_]
                             (http-handler req)))))
         starter (fn []
                   (let [server (http/start-server handler (u/sym-map port))]
                     (reset! *server server))
                   (infof "Started server on port %s." port))]
     (->TubeServer *conn-count starter *server))))

(defn run-test-server
  ([] (run-test-server 8080))
  ([port]
   (u/configure-logging)
   (let [on-connect (fn [conn conn-id path]
                      (let [on-rcv (fn [conn data]
                                     (connection/send
                                      conn (ba/reverse-byte-array data)))]
                        (connection/set-on-rcv conn on-rcv)))
         on-disconnect (fn [conn-id code reason])
         compression-type :smart
         server (make-tube-server port on-connect on-disconnect
                                  compression-type)]
     (start server))))


(defn -main
  [& args]
  (run-test-server))
