(ns deercreeklabs.tube.server
  (:require
   [bidi.ring]
   [clojure.core.async :as async]
   [deercreeklabs.tube.utils :as u]
   [org.httpkit.server :as http]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(def StopperFn (s/=> s/Any))
(def Handler (s/=> s/Any))

(def fragment-size-kb 255)

(def default-ws-handler-options
  {:on-connect (fn [ws]
                 (debugf "Websocket connected to %s" (:peer-addr ws)))
   :on-disconnect (fn [ws reason]
                    (debugf "Websocket to %s disconnected. Reason: %s"
                            (:peer-addr ws) reason))
   :on-rcv (fn [ws data]
             (debugf "Got data from %s: %s" (:peer-addr ws) data)
             (debugf "Echoing data back reversed bytes...")
             (u/send ws (u/reverse-byte-array data)))
   :on-error (fn [ws error]
               (debugf "Error on websocket to %s: %s"
                       (:peer-addr ws) error))})

(defn make-http-handler
  [status headers body]
  (u/sym-map status headers body))

(s/defn make-ws-handler :- Handler
  ([] (make-ws-handler {}))
  ([options :- u/WebSocketOptions]
   (fn ws-handler [req]
     (try
       (http/with-channel req channel
         (let [options (merge default-ws-handler-options options)
               {:keys [on-connect on-disconnect on-rcv on-error]} options
               *peer-fragment-size-kb (atom nil)
               *compress (atom nil)
               *decompress (atom nil)
               ch-str (.toString channel)
               [local remote-addr] (clojure.string/split ch-str #"<->")
               error-chan (async/chan)
               sender (fn [data]
                        (when-not (http/send! channel (@*compress data))
                          (async/put! error-chan :channel-closed)))
               closer #(http/close channel)
               ws (u/make-websocket remote-addr sender closer)
               on-rcv* (fn [data]
                         (if @*peer-fragment-size-kb
                           (on-rcv ws (@*decompress data))
                           (let [peer-fragment-size-kb (int (aget data 0))
                                 compression-type-id (int (aget data 1))
                                 compression-info (u/compression-type-id->info
                                                   compression-type-id)]
                             (reset! *peer-fragment-size-kb
                                     peer-fragment-size-kb)
                             (reset! *compress
                                     (:compress compression-info))
                             (reset! *decompress
                                     (:decompress compression-info))
                             (http/send! channel
                                         (byte-array [fragment-size-kb])))))]
           (http/on-receive channel on-rcv*)
           (http/on-close channel (partial on-disconnect ws))
           (async/take! error-chan (partial on-error ws))
           (on-connect ws)))
       (catch Exception e
         (u/log-exception e))))))

(s/defn serve :- StopperFn
  [port :- s/Num
   routes :- {s/Str Handler}]
  (let [handler (bidi.ring/make-handler ["/" routes])
        options {:port port
                 :thread (.availableProcessors (Runtime/getRuntime))}
        _ (infof "Starting server on port %s." port)
        stopper (org.httpkit.server/run-server handler options)
        stopper #(do (infof "Stopping server on port %s." port)
                     (stopper))]
    stopper))
