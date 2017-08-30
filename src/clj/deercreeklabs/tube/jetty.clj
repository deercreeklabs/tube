(ns deercreeklabs.tube.jetty
  (:require
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.net InetSocketAddress)
   (java.util UUID)
   (org.eclipse.jetty.server HttpConfiguration
                             HttpConnectionFactory
                             SecureRequestCustomizer
                             Server
                             ServerConnector
                             SslConnectionFactory)
   (org.eclipse.jetty.util.ssl SslContextFactory)
   (org.eclipse.jetty.websocket.api Session WebSocketListener)
   (org.eclipse.jetty.websocket.server WebSocketHandler)
   (org.eclipse.jetty.websocket.servlet WebSocketServletFactory)))

(defn make-listener [on-connect on-disconnect on-rcv *id->session]
  (reify WebSocketListener
    (onWebSocketConnect [this session]
      (let [id (.toString ^UUID (UUID/randomUUID))
            path ""
            remote-addr (.toString ^InetSocketAddress
                                   (.getRemoteAddress session))]
        (swap! *id->session assoc id session)
        (on-connect id path)))

    (onWebSocketClose [this code reason]
      (debugf "Disonnected: code: %s reason: %s" code reason))

    (onWebSocketError [this cause]
      (errorf "WS Error: %s" cause))

    (onWebSocketText [this s]
      (debugf "Got text: %s" s))

    (onWebSocketBinary [this ba a b]
      (debugf "Got binary. a: %s b: %s" a b))))

(defn serve [port keystore-path keystore-password on-connect
             on-disconnect on-rcv]
  (let [*id->session (atom {})
        ^Server server (Server.)
        ssl-cxf (doto (SslContextFactory. ^String keystore-path)
                  (.setKeyStorePassword keystore-password))
        wss-conn (doto (ServerConnector. ^Server server
                                         ^SslContextFactory ssl-cxf)
                   (.setPort port))
        listener (make-listener on-connect on-disconnect on-rcv *id->session)
        handler (proxy [WebSocketHandler] []
                  (configure [factory]
                    (.register ^WebSocketServletFactory factory
                               (class listener))
                    ;; TODO: configurePolicy here.
                    ))]
    (.addConnector server wss-conn)
    (.setHandler server handler)
    (.start server)
    server
    #_(.join server)))
