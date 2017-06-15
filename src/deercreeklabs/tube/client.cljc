1(ns deercreeklabs.tube.client
   (:require
    [deercreeklabs.tube.utils :as u]
    #?(:clj [gniazdo.core :as ws])
    [schema.core :as s :include-macros true]
    [taoensso.timbre :as timbre
     #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]]))

(declare make-websocket-clj)

;;;;;;;;;;;;;;;;;;;; API ;;;;;;;;;;;;;;;;;;;;

(def default-client-options
  {:on-connect (fn [ws]
                 (debugf "Websocket connected to %s" (:peer-addr ws)))
   :on-disconnect (fn [ws reason]
                    (debugf "Websocket to %s disconnected. Reason: %s"
                            (:peer-addr ws) reason))
   :on-rcv (fn [ws data]
             (debugf "Got data from %s: %s" (:peer-addr ws) data))
   :on-error (fn [ws error]
               (debugf "Error on websocket to %s: %s"
                       (:peer-addr ws) error))
   :compression-type :deflate})

(s/defn make-websocket :- (s/protocol u/IWebSocket)
  ([uri :- s/Str] (make-websocket uri {}))
  ([uri :- s/Str
    options :- u/WebSocketOptions]
   (let [options (merge default-client-options options)]
     (make-websocket-clj uri options))))

;;;;;;;;;;;;;;;;;;;; Helper fns ;;;;;;;;;;;;;;;;;;;;

(defn make-websocket-clj
  [uri options]
  (let [{:keys [on-connect on-disconnect
                on-rcv on-error compression-type-kw]} options
        *ws (atom nil)
        *peer-fragment-size-kb (atom nil)
        compression-info (u/compression-type-kw->info compression-type-kw)
        {:keys [compress decompress compression-type-id]} compression-info
        on-bin (fn [bs offset length]
                 (let [data (u/slice-byte-array bs offset (+ offset length))]
                   (if @*ws ;; if the ws is configured...
                     (on-rcv @*ws (decompress data))
                     (reset! *peer-fragment-size-kb (int (aget data 0))))))
        socket (ws/connect uri
                           :on-close (fn [status reason]
                                       (on-disconnect @*ws reason))
                           :on-error #(on-error @*ws %)
                           :on-binary on-bin)
        closer #(ws/close socket)
        sender (fn [data]
                 (try
                   ;; Send-msg mutates binary data, so we make a copy
                   (let [data (u/slice-byte-array data)]
                     (ws/send-msg socket (compress data)))
                   (catch Exception e
                     (on-error @*ws (u/get-exception-msg-and-stacktrace e)))))
        fragment-size-kb 32 ;; Jetty seems to work well w/ 32KB fragments
        header (u/byte-array [fragment-size-kb compression-type-id])
        _ (ws/send-msg socket header)
        _ (while (not @*peer-fragment-size-kb)
            (Thread/sleep 1))
        ws (u/make-websocket uri sender closer)]
    (reset! *ws ws)
    (on-connect ws)
    ws))
