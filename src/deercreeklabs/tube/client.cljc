(ns deercreeklabs.tube.client
  (:require
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]]))

;;;;;;;;;;;;;;;;;;;; Helper fns ;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (s/defn make-websocket-clj :- (s/protocol u/IWebSocket)
     [uri :- s/Str
      options :- u/WebSocketOptions]
     (let [{:keys [on-connect on-disconnect
                   on-rcv on-error compression-type]} options
           fragment-size 32000
           *ws (atom nil)
           *peer-fragment-size (atom nil)
           handle-rcv (u/make-handle-rcv on-rcv *peer-fragment-size *ws)
           on-bin (fn [bs offset length]
                    (let [data (u/slice-byte-array bs offset (+ offset length))]
                      (handle-rcv data)))
           socket (ws/connect uri
                              :on-close (fn [status reason]
                                          (on-disconnect @*ws reason))
                              :on-error #(on-error @*ws %)
                              :on-binary on-bin)
           closer #(ws/close socket)
           sender (u/make-sender
                   #(ws/send-msg socket %) on-error compression-type
                   *peer-fragment-size *ws :client)
           frag-req (u/int->zig-zag-encoded-byte-array fragment-size)
           _ (ws/send-msg socket frag-req)
           _ (while (not @*peer-fragment-size)
               (Thread/sleep 1))
           ws (u/make-websocket uri sender closer)]
       (reset! *ws ws)
       (on-connect ws)
       ws)))

#?(:cljs
   (s/defn make-websocket-node :- (s/protocol u/IWebSocket)
     [uri :- s/Str
      options :- u/WebSocketOptions]
     ))

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

(defn make-keep-alive-loop [ws keep-alive-secs]
  )

;;;;;;;;;;;;;;;;;;;; API ;;;;;;;;;;;;;;;;;;;;

(s/defn make-websocket :- (s/protocol u/IWebSocket)
  ([uri :- s/Str] (make-websocket uri {}))
  ([uri :- s/Str
    options :- u/WebSocketOptions]
   (let [options (merge u/default-websocket-options options)
         {:keys [keep-alive-secs]} options
         ws-factory #?(:clj make-websocket-clj
                       :cljs (case (u/get-platform-kw)
                               :jvm make-websocket-clj
                               :node make-websocket-node
                               :browswer make-websocket-browser
                               :jsc-ios make-websocket-jsc-ios))
         ws (ws-factory uri options)]
     (when (pos? keep-alive-secs)
       (make-keep-alive-loop ws keep-alive-secs))
     ws)))
