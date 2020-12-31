(ns deercreeklabs.tube.client
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   #?(:cljs [goog.object])
   #?(:clj [primitive-math])
   [schema.core :as s])
  #?(:clj
     (:import
      (java.net URI)
      (java.nio ByteBuffer)
      (org.java_websocket.client WebSocketClient))))

#?(:clj
   (set! *warn-on-reflection* true))
#?(:clj
   (primitive-math/use-primitive-operators))

(def default-keepalive-secs 25)

(defprotocol ITubeClient
  (send [this data] "Send binary bytes over this tube")
  (close [this] [this code reason] "Close this tube"))

(deftype TubeClient [conn close-client! *shutdown?]
  ITubeClient
  (send [this data]
    (connection/send conn data))

  (close [this]
    (let [code 1000
          reason "Explicit close"
          ws-already-closed? false]
      (close-client! code reason ws-already-closed?))))

#?(:clj
   (defn <ws-client-clj
     [logger url on-error on-disconnect handle-rcv! log-conn-failure?
      *conn *shutdown?]
     (let [ret-ch (ca/promise-chan)]
       (try
         (let [fragment-size 31999
               *connected? (atom false)
               on-error* (fn [e]
                           (if @*connected?
                             (on-error (u/ex-msg-and-stacktrace e))
                             (do
                               (when log-conn-failure?
                                 (logger :error
                                         (str "Websocket failed to connect. "
                                              "Error: " (u/ex-msg e))))
                               (ca/close! ret-ch))))
               *client (atom nil)
               sender (fn [ba]
                        (when-let [client @*client]
                          (when-not @*shutdown?
                            (.send ^WebSocketClient client (bytes ba)))))
               closer (fn []
                        (when-let [client @*client]
                          (.close ^WebSocketClient client 1000)))
               ^WebSocketClient client (proxy [WebSocketClient] [(URI. url)]
                                         (onOpen [handshake]
                                           (reset! *connected? true)
                                           (ca/put! ret-ch (u/sym-map
                                                            sender closer
                                                            fragment-size)))
                                         (onClose [code reason remote]
                                           (reset! *shutdown? true)
                                           (if @*connected?
                                             (on-disconnect @*conn code reason)
                                             (ca/close! ret-ch)))
                                         (onError [e]
                                           (on-error* e))
                                         (onMessage [^ByteBuffer bb]
                                           (let [size (.remaining bb)
                                                 ba (ba/byte-array size)]
                                             (.get bb (bytes ba))
                                             (handle-rcv! ba))))]
           (.connectBlocking client)
           (reset! *client client))
         (catch Exception e
           (when log-conn-failure?
             (logger :info (str "Websocket failed to connect. Error: "
                                (u/ex-msg e))))
           (ca/close! ret-ch)
           ret-ch))
       ret-ch)))

#?(:cljs
   (defn <ws-client-cljs
     [logger url on-error on-disconnect handle-rcv! log-conn-failure?
      *conn *shutdown?]
     (let [fragment-size 31999
           client (case (u/platform-kw)
                    :node (let [WSC (js/require "ws")]
                            (WSC. url))
                    :browser (js/WebSocket. url))
           *connected? (atom false)
           msg-handler (fn [msg-obj]
                         (let [data (js/Int8Array. (.-data msg-obj))]
                           (handle-rcv! data)))
           closer #(.close client)
           sender (fn [data]
                    (when-not @*shutdown?
                      (.send client (.-buffer data))))
           ret-ch (ca/promise-chan)]
       (set! (.-binaryType client) "arraybuffer")
       (set! (.-onopen client) (fn [event]
                                 (reset! *connected? true)
                                 (ca/put! ret-ch (u/sym-map sender closer
                                                            fragment-size))))
       (set! (.-onclose client) (fn [event]
                                  (reset! *shutdown? true)
                                  (if @*connected?
                                    (on-disconnect @*conn (.-code event)
                                                   (.-reason event))
                                    (ca/close! ret-ch))))
       (set! (.-onerror client)
             (fn [err]
               (if @*connected?
                 (on-error (u/ex-msg-and-stacktrace err))
                 (do
                   (when log-conn-failure?
                     (logger :error
                             (str "Websocket failed to connect. Error: "
                                  (u/ex-msg err))))
                   (ca/close! ret-ch)))))
       (set! (.-onmessage client) msg-handler)
       ret-ch)))


(defn start-keep-alive-loop [conn keep-alive-secs *shutdown?]
  (au/go
    (while (not @*shutdown?)
      (ca/<! (ca/timeout (* 1000 (int keep-alive-secs))))
      ;; check again in case shutdown happened while we were waiting
      (when-not @*shutdown?
        (connection/send-ping conn)))))

(defn <connect
  [wsc url logger options close-client! *conn *shutdown?]
  (au/go
    (let [{:keys [sender closer fragment-size]} wsc
          {:keys [compression-type
                  connect-timeout-ms
                  keep-alive-secs
                  log-conn-failure?
                  on-rcv]
           :or {compression-type :smart
                connect-timeout-ms 5000
                keep-alive-secs default-keepalive-secs
                log-conn-failure? true
                on-rcv (constantly nil)}} options
          ready-ch (ca/chan)
          on-connect (fn [conn]
                       (ca/put! ready-ch true))
          conn-id 0 ;; There is only one
          conn (connection/connection
                conn-id url url on-connect nil nil sender closer
                fragment-size compression-type true on-rcv)]
      (reset! *conn conn)
      (sender (ba/encode-int fragment-size))
      (let [expiry-ms (+ (#?(:clj long :cljs identity) ;; ensure primitive
                          (u/current-time-ms))
                         (#?(:clj long :cljs identity) connect-timeout-ms))]
        (loop []
          (when-not @*shutdown?
            (let [[ready? ch] (ca/alts! [ready-ch (ca/timeout 100)])]
              (cond
                (= ready-ch ch)
                (when-not @*shutdown?
                  (start-keep-alive-loop conn keep-alive-secs *shutdown?)
                  (->TubeClient conn close-client! *shutdown?))

                (> (#?(:clj long :cljs identity) (u/current-time-ms))
                   (#?(:clj long :cljs identity) expiry-ms))
                (do
                  (logger :error
                          (str "Websocket to " url " connected, but did "
                               "not complete negotiation before timeout ("
                               connect-timeout-ms " ms)"))
                  (connection/close conn 1002
                                    "Protocol negotiation timed out" false)
                  nil)

                :else
                ;; Wait for the protocol negotiation to happen
                (recur)))))))))

(s/defn <tube-client
  "Will return a connected client or a closed channel (nil) on connection
   failure or timeout."
  ([url :- s/Str]
   (<tube-client url {}))
  ([url :- s/Str
    options :- {(s/optional-key :compression-type) (s/enum :none
                                                           :smart
                                                           :deflate)
                (s/optional-key :connect-timeout-ms) s/Int
                (s/optional-key :keep-alive-secs) s/Int
                (s/optional-key :logger) (s/=> s/Any s/Keyword s/Str)
                (s/optional-key :log-conn-failure?) s/Bool
                (s/optional-key :on-disconnect) (s/=> s/Any)
                (s/optional-key :on-rcv) (s/=> s/Any)}]
   (au/go
     (let [{:keys [logger log-conn-failure? on-disconnect connect-timeout-ms]
            :or {logger u/println-logger
                 log-conn-failure? true
                 connect-timeout-ms 10000
                 on-disconnect (constantly nil)}} options
           *shutdown? (atom false)
           *conn (atom nil)
           close-client! (fn [code reason ws-already-closed?]
                           (let [conn @*conn]
                             (reset! *shutdown? true)
                             (when conn
                               (connection/close conn code reason
                                                 ws-already-closed?))
                             (on-disconnect conn code reason)))
           on-error (fn [msg]
                      (try
                        (logger :error (str "Error in websocket: " msg))
                        (close-client! 1011 msg true)
                        (catch #?(:clj Exception :cljs js/Error) e
                          (logger :error "Unexpected error in on-error.")
                          (logger :error (u/ex-msg-and-stacktrace e)))))
           handle-rcv! #(when-let [conn @*conn]
                          (connection/handle-data conn %))
           wsc-chan (#?(:clj <ws-client-clj
                        :cljs <ws-client-cljs)
                     logger url on-error on-disconnect handle-rcv!
                     log-conn-failure? *conn *shutdown?)
           [wsc ch] (au/alts? [wsc-chan (ca/timeout connect-timeout-ms)])]
       (when (and (= wsc-chan ch)
                  wsc)
         (au/<? (<connect wsc url logger options close-client!
                          *conn *shutdown?)))))))
