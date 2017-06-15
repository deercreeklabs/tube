(ns deercreeklabs.tube.client
  (:require
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]])
  #?(:clj
     (:import [java.util Arrays])))

(declare make-websocket-clj)

;;;;;;;;;;;;;;;;;;;; API ;;;;;;;;;;;;;;;;;;;;

(def default-client-options
  {:on-connect (fn [ws]
                 (debugf "Websocket connected to %s" (u/get-peer-addr ws)))
   :on-disconnect (fn [ws reason]
                    (debugf "Websocket to %s disconnected. Reason: %s"
                            (u/get-peer-addr ws) reason))
   :on-rcv (fn [ws data]
             (debugf "Got data from %s: %s" (u/get-peer-addr ws) data))
   :on-error (fn [ws error]
               (debugf "Error on websocket to %s: %s"
                       (u/get-peer-addr ws) error))})

(s/defn make-websocket :- (s/protocol u/IWebSocket)
  ([uri :- s/Str] (make-websocket uri {}))
  ([uri :- s/Str
    options :- u/WebSocketOptions]
   (let [options (merge default-client-options options)]
     (make-websocket-clj uri options))))

;;;;;;;;;;;;;;;;;;;; Helper fns ;;;;;;;;;;;;;;;;;;;;

(defn make-websocket-clj
  [uri options]
  (let [{:keys [on-connect on-disconnect on-rcv on-error]} options
        *ws (atom nil)
        on-bin (fn [bytes offset length]
                 (on-rcv @*ws (Arrays/copyOfRange
                               ^bytes bytes ^int offset
                               ^int (+ offset length))))
        socket (ws/connect uri
                           :on-close (fn [status reason]
                                       (on-disconnect @*ws reason))
                           :on-error #(on-error @*ws %)
                           :on-receive #(on-rcv @*ws %)
                           :on-binary on-bin)
        closer #(ws/close socket)
        sender (fn [data]
                 (try
                   ;; Send-msg mutates binary data, so we make a
                   ;; copy if data is binary
                   (if (string? data)
                     (ws/send-msg socket data)
                     (ws/send-msg socket (Arrays/copyOf
                                          ^bytes data ^int (count data))))
                   (catch Exception e
                     (on-error @*ws (u/get-exception-msg-and-stacktrace e)))))
        ws (u/make-websocket uri sender closer)]
    (reset! *ws ws)
    (on-connect ws)
    ws))
