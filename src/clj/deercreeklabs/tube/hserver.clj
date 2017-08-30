(ns deercreeklabs.tube.hserver
  (:require
   [org.httpkit.server :as hk]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.util UUID)))

;; (defprotocol IWebSocketServer
;;   (serve [this] "Start serving")
;;   (stop [this] "Stop serving")
;;   (send [this ws-id bs] "Send binary bytes to the specified websocket")
;;   (close [this ws-id] "Close the specified websocket"))

;; (defprotocol IWebSocket
;;   (send [this bs] "Send binary bytes over this websocket")
;;   (close [this] "Close this websocket"))

;; (defrecord WebSocket [*state id channel]
;;   IWebSocket
;;   (send [this bs]
;;     )
;;   (close [this]
;;     ))

;; (defrecord WebSocketServer [port on-connect on-disconnect on-rcv
;;                             queue-size cores *stopper *id->ws]
;;   IWebSocketServer
;;   (serve [this]
;;     (let [handler (fn [req]
;;                     (hk/with-channel req channel
;;                       (let [id (.toString ^UUID (UUID/randomUUID))
;;                             ws (->WebSocket (atom :connected))]
;;                         (swap! *id->channel id)
;;                         (hk/on-receive channel ))))
;;           cores (.availableProcessors (Runtime/getRuntime))
;;           stopper (hk/run-server handler {:port port
;;                                           :thread cores
;;                                           :queue-size 1e7})]
;;       (reset! *stopper stopper)))

;;   (stop [this]
;;     (@*stopper))

;;   (send [this ws-id bs]
;;     (when-not (hk/send! (@*id->channel ws-id) bs)
;;       (on-)))

;;   (close [this ws-id]
;;     (hk/close (@*id->channel ws-id))))

;; (defn make-websocket-server [options]
;;   (let [{:keys [port on-connect on-disconnect on-rcv queue-size cores]
;;          :or {port 8080
;;               on-connect (constantly nil)
;;               on-disconnect (constantly nil)
;;               on-rcv (constantly nil)
;;               cores (.availableProcessors (Runtime/getRuntime))
;;               queue-size 2e4}}]
;;     (->WebSocketServer port on-connect on-disconnect on-rcv
;;                        queue-size cores (atom nil) (atom {}))))
