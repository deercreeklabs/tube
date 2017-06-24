(ns deercreeklabs.tube.client
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as async]
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]]))

;;;;;;;;;;;;;;;;;;;; Helper fns ;;;;;;;;;;;;;;;;;;;;

(defn client-on-connect
  [ws-sender fragment-size rcv-chan on-connect on-error *ws *peer-fragment-size]
  (async/go
    (try
      (let [frag-req (u/int->zig-zag-encoded-byte-array fragment-size)
            _ (ws-sender frag-req)
            data (async/<! rcv-chan)
            [peer-fragment-size data] (u/read-zig-zag-encoded-int data)]
        (when (pos? (count data))
          (throw (ex-info "Extra data recieved in negotiation header."
                          {:type :execution-error
                           :subtype :extra-data-in-negotiation-header
                           :extra-data data
                           :extra-data-str
                           (u/byte-array->debug-str data)})))
        (reset! *peer-fragment-size peer-fragment-size)
        (on-connect @*ws))
      (catch #?(:clj Exception :cljs :default) e
          (on-error @*ws (u/get-exception-msg-and-stacktrace e))))))

#?(:clj
   (defn make-websocket-clj
     [uri handle-rcv rcv-chan on-connect on-disconnect on-error
      *ws *peer-fragment-size *shutdown]
     (let [fragment-size 32000
           on-bin (fn [bs offset length]
                    (let [data (u/slice-byte-array bs offset (+ offset length))]
                      (handle-rcv data)))
           socket (ws/connect uri
                              :on-close (fn [status reason]
                                          (on-disconnect @*ws reason))
                              :on-error #(on-error @*ws %)
                              :on-binary on-bin)
           ws-closer #(ws/close socket)
           ws-sender #(try
                        (ws/send-msg socket %)
                        (catch Exception e
                          (when-not @*shutdown
                            (on-error
                             @*ws (u/get-exception-msg-and-stacktrace e)))))]
       (client-on-connect ws-sender fragment-size rcv-chan on-connect on-error
                          *ws *peer-fragment-size)
       (u/sym-map ws-closer ws-sender))))

#?(:cljs
   (defn make-websocket-node
     [uri handle-rcv rcv-chan on-connect on-disconnect on-error
      *ws *peer-fragment-size *shutdown]
     (let [fragment-size 32000
           WSC (goog.object.get (js/require "websocket") "client")
           client (WSC.)
           conn-atom (atom nil)
           msg-handler (fn [msg-obj]
                         (let [type (goog.object.get msg-obj "type")
                               data (if (= "utf8" type)
                                      (goog.object.get msg-obj "utf8Data")
                                      (-> (goog.object.get msg-obj "binaryData")
                                          (js/Int8Array.)))]
                           (on-rcv data)))
           conn-handler (fn [conn]
                          (reset! conn-atom conn)
                          (on-connect)
                          (.on conn "close" (fn [reason description]
                                              (on-disconnect description)))
                          (.on conn "message" msg-handler)
                          (.on conn "error" on-error))
           ws-closer #(if @conn-atom
                        (.close @conn-atom)
                        (.abort client))
           ws-sender (fn [data]
                       (if (string? data)
                         (.sendUTF @conn-atom data)
                         (.sendBytes @conn-atom (js/Buffer. data))))
           failure-handler  (fn [err]
                              (let [reason [:connect-failure err]]
                                (on-error reason)
                                (on-disconnect reason)))]
       (.on client "connectFailed" failure-handler)
       (.on client "connect" conn-handler)
       (.connect client uri)
       (u/sym-map ws-closer ws-sender))))

#?(:cljs
   (s/defn make-websocket-browser :- (s/protocol u/IWebSocket)
     [uri :- s/Str
      options :- u/WebSocketOptions]
     ))

#?(:cljs
   (s/defn make-websocket-jsc-ios :- (s/protocol u/IWebSocket)
     [uri :- s/Str
      options :- u/WebSocketOptions]
     ))

(defn start-keep-alive-loop [ws keep-alive-secs *peer-fragment-size *shutdown]
  (async/go
    (while (not @*peer-fragment-size)
      (async/<! (async/timeout 10)))
    (while (not @*shutdown)
      (async/<! (async/timeout (int (* 1000 keep-alive-secs))))
      (u/send-ping ws))))

;;;;;;;;;;;;;;;;;;;; API ;;;;;;;;;;;;;;;;;;;;

(s/defn make-websocket :- (s/protocol u/IWebSocket)
  ([uri :- s/Str] (make-websocket uri {}))
  ([uri :- s/Str
    options :- u/WebSocketOptions]
   (let [options (merge u/default-websocket-options options)
         {:keys [on-connect on-disconnect on-rcv
                 on-error compression-type keep-alive-secs]} options
         *ws (atom nil)
         *peer-fragment-size (atom nil)
         *shutdown (atom false)
         on-error* (fn [ws error]
                     (when-not @*shutdown
                       (on-error ws error)))
         rcv-chan (async/chan)
         handle-rcv (u/make-handle-rcv on-rcv rcv-chan *peer-fragment-size *ws)
         ws-factory #?(:clj make-websocket-clj
                       :cljs (case (u/get-platform-kw)
                               :jvm make-websocket-clj
                               :node make-websocket-node
                               :browswer make-websocket-browser
                               :jsc-ios make-websocket-jsc-ios))
         ws-ret (ws-factory uri handle-rcv rcv-chan on-connect on-disconnect
                            on-error* *ws *peer-fragment-size *shutdown)
         {:keys [ws-sender ws-closer]} ws-ret
         sender (u/make-sender
                 ws-sender on-error* compression-type
                 *peer-fragment-size *ws :client)
         closer #(do
                   (reset! *shutdown true)
                   (ws-closer))
         ws (u/make-websocket uri sender ws-sender closer)]
     (reset! *ws ws)
     (when (pos? keep-alive-secs)
       (start-keep-alive-loop ws keep-alive-secs *peer-fragment-size *shutdown))
     ws)))
