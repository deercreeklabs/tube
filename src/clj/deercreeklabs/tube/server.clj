(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [bidi.ring]
   [clojure.core.async :as async]
   [deercreeklabs.tube.utils :as u]
   [org.httpkit.server :as http]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(def StopperFn (s/=> s/Any))
(def Handler (s/=> s/Any))

(def fragment-size 32000)

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

(defn server-on-connect
  [ws-sender fragment-size rcv-chan on-connect on-error *ws *peer-fragment-size]
  (async/go
    (try
      (let [frag-req (u/int->zig-zag-encoded-byte-array fragment-size)
            data (async/<! rcv-chan)
            [peer-fragment-size data] (u/read-zig-zag-encoded-int data)]
        (when (pos? (count data))
          (throw (ex-info "Extra data in negotiation header."
                          {:type :execution-error
                           :subtype :extra-data-in-negotiation-header
                           :extra-data data
                           :extra-data-str
                           (u/byte-array->debug-str data)})))
        (ws-sender frag-req)
        (reset! *peer-fragment-size peer-fragment-size)
        (on-connect @*ws))
      (catch Exception e
          (on-error @*ws (u/get-exception-msg-and-stacktrace e))))))

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
               rcv-chan (async/chan)
               handle-rcv (u/make-handle-rcv on-rcv rcv-chan
                                             *peer-fragment-size *ws)
               ch-str (.toString ^org.httpkit.server.AsyncChannel channel)
               [local remote-addr] (clojure.string/split ch-str #"<->")
               sender (u/make-sender ws-sender on-error compression-type
                                     *peer-fragment-size *ws :server)
               closer #(http/close channel)
               ws (u/make-websocket remote-addr sender ws-sender closer)]
           (reset! *ws ws)
           (http/on-receive channel handle-rcv)
           (http/on-close channel (partial on-disconnect ws))
           (server-on-connect ws-sender fragment-size rcv-chan on-connect
                              on-error *ws *peer-fragment-size)))
       (catch Exception e
         (u/log-exception e))))))

(s/defn serve :- StopperFn
  [port :- s/Num
   routes :- {s/Str Handler}]
  (let [handler (bidi.ring/make-handler ["/" routes])
        options {:port port
                 :thread (.availableProcessors (Runtime/getRuntime))}
        stopper (org.httpkit.server/run-server handler options)
        _ (infof "Server started on port %s." port)
        stopper #(do (infof "Stopping server on port %s." port)
                     (stopper))]
    stopper))

(s/defn run-reverser-server :- StopperFn
  ([] (run-reverser-server 8080))
  ([port]
   (u/configure-logging)
   (let [routes {"" (make-ws-handler)}
         stop-server (serve port routes)]
     stop-server)))

(defn -main
  [& args]
  (run-reverser-server))
