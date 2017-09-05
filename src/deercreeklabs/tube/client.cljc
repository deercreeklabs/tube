(ns deercreeklabs.tube.client
  (:refer-clojure :exclude [send])
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as ca]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   #?(:cljs [goog.object])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]])
  #?(:clj
     (:import
      (java.net URI)
      (org.java_websocket.client WebSocketClient)
      (org.java_websocket.handshake ServerHandshake))))

#?(:clj
   (primitive-math/use-primitive-operators))

(defn start-keep-alive-loop [conn keep-alive-secs *shutdown]
  (u/go-sf
   (while (not @*shutdown)
     (ca/<! (ca/timeout (* 1000 (int keep-alive-secs))))
     ;; check again in case shutdown happened while we were waiting
     (when-not @*shutdown
       (connection/send-ping conn)))))

(defprotocol ITubeClient
  (send [this data] "Send binary bytes over this tube")
  (close [this] [this code reason] "Close this tube"))

(deftype TubeClient [conn]
  ITubeClient
  (send [this data]
    (connection/send conn data))

  (close [this]
    (connection/close conn)))

;; #?(:clj
;;    (defn <make-ws-client-clj-ttn [uri connected-ch *handle-rcv *close-client]
;;      (u/go-sf
;;       (let [fragment-size 31999
;;             wsc (proxy [WebSocketClient] [(URI. uri)]
;;                   (onOpen [^ServerHandshake handshakedata]
;;                     (ca/put! connected-ch true))
;;                   (onMessage [message]
;;                     (@*handle-rcv message))
;;                   (onClose [code reason remote?]
;;                     (@*close-client code reason))
;;                   (onError [^Exception e]
;;                     (let [msg (u/get-exception-msg-and-stacktrace e)]
;;                       (errorf "Error in websocket: %s" msg)
;;                       (@*close-client 1011 msg))))
;;             sender #(.send ^WebSocketClient wsc ^bytes %)
;;             closer #(.close ^WebSocketClient wsc)]
;;         (.connect ^WebSocketClient wsc)
;;         (u/sym-map sender closer fragment-size)))))

#?(:clj
   (defn <make-ws-client-clj
     [uri connected-ch on-error *handle-rcv *close-client]
     (u/go-sf
      (let [fragment-size 31999
            on-bin (fn [bs offset length]
                     (let [data (u/slice-byte-array
                                 bs offset (+ (int offset) (int length)))]
                       (@*handle-rcv data)))
            socket (ws/connect
                    uri
                    :on-close (fn [code reason]
                                (@*close-client code reason))
                    :on-error on-error
                    :on-binary on-bin)
            _ (ca/put! connected-ch true) ;; ws/connect returns when connected
            closer #(ws/close socket)
            sender #(try
                      (ws/send-msg socket %)
                      (catch Exception e
                        (on-error (u/get-exception-msg-and-stacktrace e))))]
        (u/sym-map sender closer fragment-size)))))

;; TODO: Replace .property access with cljs-oops or goog.object.*
#?(:cljs
   (defn <make-ws-client-node
     [uri connected-ch on-error *handle-rcv *close-client]
     (u/go-sf
      (let [fragment-size 32000
            WSC (goog.object.get (js/require "websocket") "client")
            client (WSC.)
            *conn (atom nil)
            msg-handler (fn [msg-obj]
                          (let [data (-> (goog.object.get msg-obj "binaryData")
                                         (js/Int8Array.))]
                            (@*handle-rcv data)))
            closer #(if @*conn
                      (.close @*conn)
                      (.abort client))
            sender (fn [data]
                     (.sendBytes @*conn (js/Buffer. data)))
            conn-handler (fn [conn]
                           (.on conn "close" (fn [code reason]
                                               (@*close-client code reason)))
                           (.on conn "error" on-error)
                           (.on conn "message" msg-handler)
                           (reset! *conn conn)
                           (ca/put! connected-ch true))
            failure-handler  (fn [err]
                               (let [reason [:connect-failure err]]
                                 (on-error reason)))]
        (.on client "connectFailed" failure-handler)
        (.on client "connect" conn-handler)
        (.connect client uri)
        (u/sym-map sender closer fragment-size)))))

#?(:cljs
   (defn <make-ws-client-browser
     [uri connected-ch on-error *handle-rcv *close-client]
     (u/go-sf
      (let [fragment-size 32000
            client (js/WebSocket. uri)
            msg-handler (fn [msg-obj]
                          (let [data (-> (goog.object.get msg-obj "binaryData")
                                         (js/Int8Array.))]
                            (@*handle-rcv data)))
            closer #(.close client)
            sender (fn [data]
                     (.send client (.buffer data)))]
        (set! (.-binaryType client) "arraybuffer")
        (set! (.-onopen client) (fn [event]
                                  (ca/put! connected-ch true)))
        (set! (.-onclose client) (fn [event]
                                   (@*close-client (.-code event)
                                    (.-reason event))))
        (set! (.-onerror client) on-error)
        (set! (.-onmessage client) msg-handler)
        (u/sym-map sender closer fragment-size)))))

#?(:cljs
   (defn <make-ws-client-jsc-ios
     [uri connected-ch on-error *handle-rcv *close-client]
     ;; TODO: Implement
     ))

(defn <make-ws-client [& args]
  (let [factory #?(:clj <make-ws-client-clj
                   :cljs (case (u/get-platform-kw)
                           :node <make-ws-client-node
                           :jsc-ios <make-ws-client-jsc-ios
                           :browser <make-ws-client-browser))]
    (apply factory args)))

(defn <make-tube-client [uri options]
  (u/go-sf
   (let [{:keys [compression-type keep-alive-secs on-disconnect on-rcv]
          :or {compression-type :smart
               keep-alive-secs 25
               on-disconnect (constantly nil)
               on-rcv (constantly nil)}} options
         *handle-rcv (atom nil)
         *close-client (atom nil)
         *shutdown (atom false)
         connected-ch (ca/chan)
         on-error (fn [msg]
                    (errorf "Error in websocket: %s" msg)
                    (@*close-client 1011 msg))
         wsc (u/call-sf! <make-ws-client uri connected-ch on-error *handle-rcv
                         *close-client)
         {:keys [sender closer fragment-size]} wsc
         close-client (fn [code reason]
                        (reset! *shutdown true)
                        (closer)
                        (on-disconnect code reason))
         conn (connection/make-connection uri sender closer nil
                                          compression-type true on-rcv)]
     (reset! *handle-rcv #(connection/handle-data conn %))
     (reset! *close-client close-client)
     (ca/<! connected-ch)
     (sender (u/encode-int fragment-size))
     ;; Wait for the protocol negotiation to happen
     (while (= :connected (connection/get-state conn))
       (ca/<! (ca/timeout 100)))
     (start-keep-alive-loop conn keep-alive-secs *shutdown)
     (->TubeClient conn))))
