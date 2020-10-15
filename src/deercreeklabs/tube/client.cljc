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

(deftype TubeClient [conn *shutdown?]
  ITubeClient
  (send [this data]
    (connection/send conn data))

  (close [this]
    (reset! *shutdown? true)
    (connection/close conn)))

#?(:clj
   (defn <ws-client-clj
     [logger url connected-ch on-error *handle-rcv *close-client
      log-conn-failure?]
     (ca/go
       (try
         (let
             [fragment-size 31999
              *connected? (atom false)
              on-error* (fn [e]
                          (if @*connected?
                            (on-error (u/ex-msg-and-stacktrace e))
                            (do
                              (when log-conn-failure?
                                (logger :error
                                        (str "Websocket failed to connect. "
                                             "Error: "
                                             (u/ex-msg-and-stacktrace e))))
                              (ca/put! connected-ch false))))
              ^WebSocketClient client (proxy [WebSocketClient] [(URI. url)]
                                        (onOpen [handshake]
                                          (reset! *connected? true)
                                          (ca/put! connected-ch true))
                                        (onClose [code reason remote]
                                          (@*close-client code reason true))
                                        (onError [e]
                                          (on-error* e))
                                        (onMessage [^ByteBuffer bb]
                                          (let [size (.remaining bb)
                                                ba (ba/byte-array size)]
                                            (.get bb (bytes ba))
                                            (@*handle-rcv ba))))
              sender #(.send client (bytes %))
              closer #(.close client 1000)]
           (.connect client)
           (u/sym-map sender closer fragment-size))
         (catch Exception e
           (when log-conn-failure?
             (logger :info (str "Websocket failed to connect. Error: "
                                (u/ex-msg e))))
           (ca/put! connected-ch false)
           nil)))))

#?(:cljs
   (defn <ws-client-cljs
     [logger url connected-ch on-error *handle-rcv *close-client
      log-conn-failure?]
     (au/go
       (let [fragment-size 31999
             client (case (u/platform-kw)
                      :node (let [WSC (js/require "ws")]
                              (WSC. url))
                      :browser (js/WebSocket. url))
             *connected? (atom false)
             msg-handler (fn [msg-obj]
                           (let [data (js/Int8Array. (.-data msg-obj))]
                             (@*handle-rcv data)))
             closer #(.close client)
             sender (fn [data]
                      (.send client (.-buffer data)))]
         (set! (.-binaryType client) "arraybuffer")
         (set! (.-onopen client) (fn [event]
                                   (reset! *connected? true)
                                   (ca/put! connected-ch true)))
         (set! (.-onclose client) (fn [event]
                                    (@*close-client (.-code event)
                                     (.-reason event) true)))
         (set! (.-onerror client)
               (fn [err]
                 (if @*connected?
                   (on-error err)
                   (do
                     (when log-conn-failure?
                       (logger :error
                               (str "Websocket failed to connect. Error: "
                                    (u/ex-msg err))))
                     (ca/put! connected-ch false)))))
         (set! (.-onmessage client) msg-handler)
         (u/sym-map sender closer fragment-size)))))


(defn start-keep-alive-loop [conn keep-alive-secs *shutdown?]
  (au/go
    (while (not @*shutdown?)
      (ca/<! (ca/timeout (* 1000 (int keep-alive-secs))))
      ;; check again in case shutdown happened while we were waiting
      (when-not @*shutdown?
        (connection/send-ping conn)))))

(defn <connect [wsc url logger options *handle-rcv *close-client connected-ch]
  (au/go
    (let [{:keys [sender closer fragment-size]} wsc
          {:keys [compression-type keep-alive-secs on-disconnect on-rcv
                  log-conn-failure? connect-timeout-ms]
           :or {compression-type :smart
                keep-alive-secs default-keepalive-secs
                on-disconnect (constantly nil)
                on-rcv (constantly nil)
                log-conn-failure? true
                connect-timeout-ms 5000}} options
          *shutdown? (atom false)
          ready-ch (ca/chan)
          on-connect (fn [conn]
                       (ca/put! ready-ch true))
          conn-id 0 ;; There is only one
          conn (connection/connection
                conn-id url url on-connect nil nil sender closer
                fragment-size compression-type true on-rcv)
          close-client (fn [code reason ws-already-closed?]
                         (reset! *shutdown? true)
                         (connection/close conn code reason ws-already-closed?)
                         (on-disconnect conn code reason))
          _ (reset! *handle-rcv #(connection/handle-data conn %))
          _ (reset! *close-client close-client)
          [connected? ch] (ca/alts! [connected-ch
                                     (ca/timeout connect-timeout-ms)])]
      (if-not (and (= connected-ch ch)
                   connected?)
        (do
          (when log-conn-failure?
            (logger :error (str "Websocket to " url " failed to connect.")))
          (connection/close conn 1000 "Failure to connect" false)
          nil)
        (do
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
                      (->TubeClient conn *shutdown?))

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
                    (recur)))))))))))

(defn <ws-client* [& args]
  (let [factory #?(:clj <ws-client-clj
                   :cljs <ws-client-cljs)]
    (apply factory args)))

(s/defn <tube-client
  "Will return a connected client or a closed channel (nil) on connection
   failure or timeout."
  ([url :- s/Str
    connect-timeout-ms :- s/Int]
   (<tube-client url connect-timeout-ms {}))
  ([url :- s/Str
    connect-timeout-ms :- s/Int
    options :- {(s/optional-key :compression-type) (s/enum :none :smart
                                                           :deflate)
                (s/optional-key :keep-alive-secs) s/Int
                (s/optional-key :on-disconnect) (s/=> s/Any)
                (s/optional-key :on-rcv) (s/=> s/Any)
                (s/optional-key :log-conn-failure?) s/Bool
                (s/optional-key :logger) (s/=> s/Any s/Keyword s/Str)
                (s/optional-key :connect-timeout-ms) s/Int
                (s/optional-key :<ws-client) (s/=> s/Any)}]
   (au/go
     (let [*handle-rcv (atom nil)
           *close-client (atom nil)
           connected-ch (ca/promise-chan)
           {:keys [<ws-client logger log-conn-failure?]
            :or {logger u/println-logger
                 log-conn-failure? true}} options
           on-error (fn [msg]
                      (try
                        (logger :error (str "Error in websocket: " msg))
                        (when-let [close-client @*close-client]
                          (close-client 1011 msg true))
                        (catch #?(:clj Exception :cljs js/Error) e
                          (logger :error "Unexpected error in on-error.")
                          (logger :error (u/ex-msg-and-stacktrace e)))))
           <ws-client (or <ws-client <ws-client*)
           wsc (au/<? (<ws-client logger url connected-ch on-error *handle-rcv
                                  *close-client log-conn-failure?))]
       (when wsc
         (au/<? (<connect wsc url logger options *handle-rcv *close-client
                          connected-ch)))))))
