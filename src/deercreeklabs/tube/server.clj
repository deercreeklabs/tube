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

(def fragment-size 200000)

(def default-server-ws-options
  (assoc u/default-websocket-options
         :on-rcv (fn [ws data]
                   (debugf "Server got %s bytes from %s."
                           (count data) (:peer-addr ws))
                   (let [reversed (u/reverse-byte-array data)]
                     (debugf "Done reversing bytes")
                     (u/send ws reversed))
                   (debugf "Done sending reversed bytes..."))))

(defn make-http-handler
  [status headers body]
  (u/sym-map status headers body))


(s/defn make-ws-handler :- Handler
  ([] (make-ws-handler {}))
  ([options :- u/WebSocketOptions]
   (fn ws-handler [req]
     (try
       (http/with-channel req channel
         (let [options (merge default-server-ws-options options)
               {:keys [on-connect on-disconnect
                       compression-type on-rcv on-error]} options
               *peer-fragment-size (atom nil)
               *ws (atom nil)
               ws-sender #(when-not (http/send! channel %)
                            (on-error @*ws :channel-closed))
               on-negotiate #(ws-sender (u/int->zig-zag-encoded-byte-array
                                         fragment-size))
               handle-rcv (u/make-handle-rcv on-rcv *peer-fragment-size *ws
                                             on-negotiate)
               ch-str (.toString ^org.httpkit.server.AsyncChannel channel)
               [local remote-addr] (clojure.string/split ch-str #"<->")
               sender (u/make-sender ws-sender on-error compression-type
                                     *peer-fragment-size *ws :server)
               closer #(http/close channel)
               ws (u/make-websocket remote-addr sender closer)]
           (reset! *ws ws)
           (http/on-receive channel handle-rcv)
           (http/on-close channel (partial on-disconnect ws))
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
