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

(defn make-http-handler
  [status headers body]
  (u/sym-map status headers body))

(def default-ws-handler-options
  {:on-connect (fn [ws]
                 (debugf "Websocket connected to %s" (u/get-peer-addr ws)))
   :on-disconnect (fn [ws reason]
                    (debugf "Websocket to %s disconnected. Reason: %s"
                            (u/get-peer-addr ws) reason))
   :on-rcv (fn [ws data]
             (debugf "Got data from %s: %s" (u/get-peer-addr ws) data)
             (debugf "Echoing data back...")
             (u/send ws data)) ;; Echo the received data
   :on-error (fn [ws error]
               (debugf "Error on websocket to %s: %s"
                       (u/get-peer-addr ws) error))})


(s/defn make-ws-handler :- Handler
  ([] (make-ws-handler {}))
  ([options :- u/WebSocketOptions]
   (fn ws-handler [req]
     (try
       (http/with-channel req channel
         (let [error-chan (async/chan 10)
               sender (fn [data]
                        (when-not (http/send! channel data)
                          (async/put! error-chan :channel-closed)))
               closer #(http/close channel)
               ch-str (.toString channel)
               [local remote-addr] (clojure.string/split ch-str #"<->")
               ws (u/make-websocket remote-addr sender closer)
               options (merge default-ws-handler-options options)
               {:keys [on-connect on-disconnect on-rcv on-error]} options]
           (http/on-close channel (partial on-disconnect ws))
           (http/on-receive channel (partial on-rcv ws))
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
