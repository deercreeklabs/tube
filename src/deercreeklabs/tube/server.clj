(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.net InetSocketAddress)
   (java.nio HeapByteBuffer)
   (java.security KeyStore)
   (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory)
   (org.java_websocket WebSocket)
   (org.java_websocket.handshake ClientHandshake)
   (org.java_websocket.server DefaultSSLWebSocketServerFactory
                              WebSocketServer)))

(primitive-math/use-primitive-operators)

(def fragment-size 32000)

(defprotocol ITubeServer
  (start [this] "Start serving")
  (stop [this] "Stop serving")
  (get-conn-count [this] "Return the number of current connections"))

(deftype TubeServer [*conn-id->conn ws-server]
  ITubeServer
  (start [this]
    (.start ^WebSocketServer ws-server))

  (stop [this]
    (.stop ^WebSocketServer ws-server))

  (get-conn-count [this]
    (count @*conn-id->conn)))

(defn ws->conn-id [^WebSocket ws]
  (.toString (.getRemoteSocketAddress ws)))

(defn make-ws-server
  [port on-connect on-disconnect compression-type *conn-id->conn]
  (let [iaddr (InetSocketAddress. port)
        close-conn (fn [^WebSocket ws code reason]
                     (let [conn-id (ws->conn-id ws)
                           conn (@*conn-id->conn conn-id)]
                       (when conn
                         (connection/close conn code reason))
                       (swap! *conn-id->conn dissoc conn-id)
                       (on-disconnect conn-id code reason)))]
    (proxy [WebSocketServer] [iaddr]
      (onOpen [^WebSocket ws ^ClientHandshake handshake]
        (let [conn-id (ws->conn-id ws)
              sender (fn [data]
                       (.send ^WebSocket ws #^bytes data))
              closer (fn [] (.close ^WebSocket ws))
              path (.getResourceDescriptor ws)
              conn (connection/make-connection
                    conn-id on-connect path sender closer fragment-size
                    compression-type false)]
          (swap! *conn-id->conn assoc conn-id conn)))
      (onClose [^WebSocket ws code ^String reason remote?]
        (close-conn ws code reason))
      (onError [^WebSocket ws ^Exception e]
        (errorf "Error on websocket.\n%s"
                (lu/get-exception-msg-and-stacktrace e))
        (close-conn ws 1011 (lu/get-exception-msg e)))
      (onMessage [^WebSocket ws ^HeapByteBuffer message]
        (let [conn-id (ws->conn-id ws)
              conn (@*conn-id->conn conn-id)
              bs (.array message)]
          (connection/handle-data conn bs)))
      (onStart []
        (infof "TubeServer starting on port %s" port)))))

(defn make-tube-server
  [port keystore-path keystore-password on-connect on-disconnect
   compression-type]
  (let [*conn-id->conn (atom {})
        ks (KeyStore/getInstance "JKS")
        _ (.load ks (io/input-stream keystore-path),
                 (.toCharArray ^String keystore-password))
        kmf (KeyManagerFactory/getInstance "SunX509")
        _ (.init kmf ks (.toCharArray ^String keystore-password))
        tmf (TrustManagerFactory/getInstance "SunX509")
        _ (.init tmf ks)
        ssl-ctx (SSLContext/getInstance "TLS")
        _ (.init ssl-ctx (.getKeyManagers kmf) (.getTrustManagers tmf) nil)
        ws-server (make-ws-server port on-connect on-disconnect
                                  compression-type *conn-id->conn)]
    (.setWebSocketFactory ^WebSocketServer ws-server
                          (DefaultSSLWebSocketServerFactory. ssl-ctx))
    (->TubeServer *conn-id->conn ws-server)))

(defn run-reverser-server
  ([] (run-reverser-server 8080))
  ([port]
   (u/configure-logging)
   (let [keystore-path (System/getenv "TUBE_JKS_KEYSTORE_PATH")
         keystore-password (System/getenv "TUBE_JKS_KEYSTORE_PASSWORD")
         on-connect (fn [conn conn-id path]
                      (let [on-rcv (fn [conn data]
                                     (connection/send
                                      conn (ba/reverse-byte-array data)))]
                        (connection/set-on-rcv conn on-rcv)))
         on-disconnect (fn [conn-id code reason])
         compression-type :smart
         server (make-tube-server port keystore-path keystore-password
                                  on-connect on-disconnect compression-type)]
     (start server))))


(defn -main
  [& args]
  (run-reverser-server))
