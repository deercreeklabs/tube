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
   [org.httpkit.server :as http]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.nio HeapByteBuffer)
   (org.httpkit.server AsyncChannel)))

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
      (do
        (reset! *stopper (starter))
        (infof "Started server."))))

  (stop [this]
    (if-let [stopper @*stopper]
      (do
        (stopper)
        (reset! *stopper nil))
      (infof "Server is not running.")))

  (get-conn-count [this]
    @*conn-count))

(defn make-root-handler [on-connect on-disconnect compression-type *conn-count]
  (fn handle [req]
    (try
      (http/with-channel req channel
        (let [{:keys [uri]} req
              ch-str (.toString ^AsyncChannel channel)
              [local remote-addr] (clojure.string/split ch-str #"<->")
              sender (fn [data]
                       (http/send! channel data))
              closer #(http/close channel)
              conn (connection/make-connection
                    remote-addr on-connect uri sender closer fragment-size
                    compression-type false)
              on-rcv #(connection/handle-data conn %)]
          (http/on-receive channel on-rcv)
          (http/on-close channel
                         (fn [reason]
                           (swap! *conn-count #(dec (int %)))
                           (debugf (str "Closed conn on %s from %s. "
                                        "Reason: %s.  Conn count: %s.")
                                   uri remote-addr reason @*conn-count)
                           (on-disconnect remote-addr "" reason)))
          (swap! *conn-count #(inc (int %)))
          (debugf "Got conn on %s from %s. Conn count: %d"
                  uri remote-addr @*conn-count)))
      (catch Exception e
        (errorf "Unexpected exception in root handler.")
        (lu/log-exception e)))))

(defn make-tube-server [port on-connect on-disconnect compression-type]
  (let [*conn-count (atom 0)
        *stopper (atom nil)
        routes ["/" {[[#".*" :path]] (make-root-handler on-connect on-disconnect
                                                        compression-type
                                                        *conn-count)}]
        handler (br/make-handler routes)
        starter #(http/run-server handler {:port port})]
    (->TubeServer *conn-count starter *stopper)))

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
