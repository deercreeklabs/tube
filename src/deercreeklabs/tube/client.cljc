(ns deercreeklabs.tube.client
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   #?(:cljs [goog.object])
   #?(:clj [primitive-math])
   [schema.core :as s])
  #?(:clj
     (:import
      (java.net ConnectException URI))))

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
         (let [fragment-size 31999
               on-bin (fn [bs offset length]
                        (let [data (ba/slice-byte-array
                                    bs offset (+ (int offset) (int length)))]
                          (@*handle-rcv data)))
               socket (ws/connect
                       url
                       :on-close (fn [code reason]
                                   (@*close-client code reason true))
                       :on-error on-error
                       :on-binary on-bin
                       :on-connect (fn [session]
                                     (ca/put! connected-ch true)))
               closer #(ws/close socket)
               sender (fn [data]
                        (try
                          ;; Send-msg mutates binary data, so we make a copy
                          (ws/send-msg socket (ba/slice-byte-array data))
                          (catch Exception e
                            (on-error
                             (u/ex-msg-and-stacktrace e)))))]
           (u/sym-map sender closer fragment-size))
         (catch Exception e
           (when log-conn-failure?
             (logger :info "Websocket failed to connect. Error: %s" e))
           (ca/put! connected-ch false)
           nil)))))

#?(:cljs
   (defn <ws-client-node
     [logger url connected-ch on-error *handle-rcv *close-client
      log-conn-failure?]
     (au/go
       (let [fragment-size 32000
             WSC (goog.object.get (js/require "websocket") "client")
             ^js/WebSocketClient client (WSC.)
             *conn (atom nil)
             msg-handler (fn [msg-obj]
                           (let [data (-> (goog.object.get msg-obj "binaryData")
                                          (js/Int8Array.))]
                             (@*handle-rcv data)))
             closer #(if @*conn
                       (.close ^js/WebSocketConnection @*conn)
                       (.abort client))
             sender (fn [data]
                      (.sendBytes ^js/WebSocketConnection @*conn
                                  (js/Buffer. data)))
             conn-handler (fn [^js/WebSocketConnection conn]
                            (.on conn "close" (fn [code reason]
                                                (@*close-client
                                                 code reason true)))
                            (.on conn "error" on-error)
                            (.on conn "message" msg-handler)
                            (reset! *conn conn)
                            (ca/put! connected-ch true))]
         (.on client "connectFailed"
              (fn [err]
                (when log-conn-failure?
                  (logger (str "Websocket failed to connect. Error: " err)))
                (ca/put! connected-ch false)))
         (.on client "connect" conn-handler)
         (.connect ^js/WebSocketClient client url)
         (u/sym-map sender closer fragment-size)))))

#?(:cljs
   (defn <ws-client-browser
     [logger url connected-ch on-error *handle-rcv *close-client
      log-conn-failure?]
     (au/go
       (let [fragment-size 32000
             client (js/WebSocket. url)
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
                       (logger
                        (str "Websocket failed to connect. Error: " err)))
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

(defn <connect [wsc url options *handle-rcv *close-client connected-ch]
  (au/go
    (let [{:keys [sender closer fragment-size]} wsc
          {:keys [compression-type keep-alive-secs on-disconnect on-rcv
                  log-conn-failure? connect-timeout-ms logger]
           :or {compression-type :smart
                keep-alive-secs default-keepalive-secs
                on-disconnect (constantly nil)
                on-rcv (constantly nil)
                log-conn-failure? true
                connect-timeout-ms 5000
                logger u/noop-logger}} options
          *shutdown? (atom false)
          ready-ch (ca/chan)
          on-connect (fn [conn]
                       (ca/put! ready-ch true))
          conn-id 0 ;; There is only one
          conn (connection/connection
                conn-id url url on-connect sender closer nil compression-type
                true on-rcv)
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
                   :cljs (case (u/platform-kw)
                           :node <ws-client-node
                           :browser <ws-client-browser))]
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
           connected-ch (ca/chan)
           {:keys [<ws-client logger log-conn-failure?]
            :or {logger u/noop-logger}} options
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
         (au/<? (<connect wsc url options *handle-rcv *close-client
                          connected-ch)))))))
