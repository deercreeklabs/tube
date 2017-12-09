(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [immutant.util :as iu]
   [immutant.web :as iw]
   [immutant.web.async :as iwa]
   [immutant.web.undertow :as iwu]
   [less.awful.ssl :as las]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.nio HeapByteBuffer)))

(primitive-math/use-primitive-operators)

(defprotocol ITubeServer
  (start [this] "Start serving")
  (stop [this] "Stop serving")
  (get-conn-count [this] "Return the number of current connections"))

(deftype TubeServer [*conn-count starter *stopper]
  ITubeServer
  (start [this]
    (if @*stopper
      (infof "Server is already started.")
      (reset! *stopper (starter))))

  (stop [this]
    (if-let [stopper @*stopper]
      (do
        (stopper)
        (reset! *stopper nil))
      (infof "Server is not running.")))

  (get-conn-count [this]
    @*conn-count))

(defn make-ws-handler
  [on-connect on-disconnect compression-type *conn-count *conn-id]
  (fn handle-ws [req]
    (try
      (let [{:keys [uri remote-addr]} req
            fragment-size 200000
            conn-id (swap! *conn-id #(inc (int %)))
            *channel (atom nil)
            sender (fn [data]
                     (when-let [channel @*channel]
                       (iwa/send! channel data)))
            closer #(when-let [channel @*channel]
                      (iwa/close channel))
            conn (connection/make-connection
                  conn-id on-connect uri sender closer fragment-size
                  compression-type false)
            on-open (fn [channel]
                      (swap! *conn-count #(inc (int %)))
                      (reset! *channel channel)
                      (debugf "Got conn on %s from %s. Conn count: %d"
                              uri remote-addr @*conn-count))
            on-close  (fn [channel {:keys [code reason]}]
                        (swap! *conn-count #(dec (int %)))
                        (debugf (str "Closed conn on %s from %s. "
                                     "Reason: %s.  Conn count: %s.")
                                uri remote-addr reason @*conn-count)
                        (on-disconnect conn-id code reason))
            on-error (fn [channel e]
                       (let [msg (lu/get-exception-msg e)]
                         (when-not (clojure.string/includes?
                                    msg "Connection reset by peer")
                           (errorf "Error in websocket:")
                           (lu/log-exception e))))
            on-message (fn [channel message]
                         (connection/handle-data conn message))]
        (iwa/as-channel req (u/sym-map on-open on-close on-error on-message)))
      (catch Exception e
        (errorf "Unexpected exception in handle-ws")
        (lu/log-exception e)))))

(defn make-http-handler [<handle-http http-timeout-ms]
  (fn handle-http [req]
    (let [on-open (fn [stream]
                    (ca/go
                      (try
                        (let [ret-ch (<handle-http req)
                              timeout-ch (ca/timeout (or http-timeout-ms 1000))
                              [ret ch] (au/alts? [ret-ch timeout-ch])
                              ret (cond
                                    (map? ret) ret
                                    (string? ret) {:status 200 :body ret}
                                    (= timeout-ch ch) {:status 504 :body ""}
                                    :else {:status 500 :body "Bad response"})]
                          (iwa/send! stream ret {:close? true}))
                        (catch Exception e
                          (errorf "Unexpected exception in http-handler.")
                          (lu/log-exception e)))))]
      (iwa/as-channel req (u/sym-map on-open)))))

(defn <handle-http-test [req]
  (au/go
    (let [{:keys [body]} req]
      (clojure.string/upper-case (slurp body)))))

(defn make-tube-server
  ([port on-connect on-disconnect compression-type]
   (make-tube-server port on-connect on-disconnect compression-type {}))
  ([port on-connect on-disconnect compression-type opts]
   (let [{:keys [<handle-http
                 http-timeout-ms
                 keystore
                 keystore-password]} opts
         *conn-count (atom 0)
         *stopper (atom nil)
         *conn-id (atom 0)
         ws-handler (make-ws-handler on-connect on-disconnect compression-type
                                     *conn-count *conn-id)
         http-handler (if <handle-http
                        (make-http-handler <handle-http http-timeout-ms)
                        (make-http-handler <handle-http-test 1000))
         handler (fn [req]
                   (if (:websocket? req)
                     (ws-handler req)
                     (http-handler req)))
         buffer-size 256000 ;; Must be bigger than fragment-size above
         options (if keystore
                   (iwu/options :ssl-port port
                                :keystore keystore
                                :key-password keystore-password
                                :buffer-size buffer-size)
                   (iwu/options :port port
                                :buffer-size buffer-size))
         starter (fn []
                   (iw/run handler options)
                   (infof "Started server on port %s." port))]
     (->TubeServer *conn-count starter *stopper))))

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
         opts {:keystore (System/getenv "TUBE_JKS_KEYSTORE_PATH")
               :keystore-password (System/getenv "TUBE_JKS_KEYSTORE_PASSWORD")}
         server (make-tube-server port on-connect on-disconnect compression-type
                                 ;; opts
                                  )]
     (start server))))


(defn -main
  [& args]
  (run-test-server))
