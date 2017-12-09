(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [bidi.ring :as br]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [immutant.web :as iw]
   [immutant.web.async :as iwa]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.nio HeapByteBuffer)))

(primitive-math/use-primitive-operators)

(def fragment-size 32000)

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
            fragment-size 15000
            conn-id (swap! *conn-id #(inc (int %)))
            *channel (atom nil)
            sender (fn [data]
                     (iwa/send! @*channel data))
            closer #(iwa/close @*channel)
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
        (errorf "Unexpected exception in root handler.")
        (lu/log-exception e)))))

(defn make-tube-server
  ([port on-connect on-disconnect compression-type]
   (make-tube-server port on-connect on-disconnect compression-type {}))
  ([port on-connect on-disconnect compression-type opts]
   (let [{:keys [http-path <handle-http]} opts
         *conn-count (atom 0)
         *stopper (atom nil)
         *conn-id (atom 0)
         ws-handler (make-ws-handler on-connect on-disconnect compression-type
                                     *conn-count *conn-id)
         routes ["/" ws-handler]
         handler (br/make-handler routes)
         starter (fn []
                   (iw/run handler {:port port})
                   (infof "Started server on port %s." port))]
     (->TubeServer *conn-count starter *stopper))))

(defn run-reverser-server
  ([] (run-reverser-server 8080))
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
  (run-reverser-server))
