(ns deercreeklabs.tube.server
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [primitive-math])
  (:import
   (java.io ByteArrayInputStream
            FileInputStream)
   (java.net InetAddress
             InetSocketAddress)
   (java.nio ByteBuffer)
   (java.security KeyFactory
                  KeyStore
                  PrivateKey
                  Security)
   (java.security.cert Certificate
                       CertificateFactory
                       X509Certificate)
   (java.security.interfaces RSAPrivateKey)
   (java.security.spec PKCS8EncodedKeySpec)
   (javax.net.ssl KeyManagerFactory
                  SSLContext
                  TrustManagerFactory)
   (javax.xml.bind DatatypeConverter)
   (org.java_websocket.handshake ClientHandshake)
   (org.java_websocket AbstractWebSocket
                       WebSocket)
   (org.java_websocket.server DefaultSSLWebSocketServerFactory
                              WebSocketServer)))

(set! *warn-on-reflection* true)
(primitive-math/use-primitive-operators)

(defn str->input-stream [s]
  (ByteArrayInputStream. (.getBytes ^String s)))

(defn make-private-key [pkey-str]
  (let [pattern (re-pattern (str "-----BEGIN PRIVATE KEY-----\n"
                                 "([^-]+)"
                                 "\n-----END PRIVATE KEY-----"))
        contents (second (re-find pattern pkey-str))
        ba (DatatypeConverter/parseBase64Binary contents)
        spec (PKCS8EncodedKeySpec. ba)
        ^KeyFactory factory (KeyFactory/getInstance "RSA")]
    (.generatePrivate factory spec)))

(defn make-cert [cert-str]
  (let [^CertificateFactory factory (CertificateFactory/getInstance "X.509")]
    (.generateCertificate factory (str->input-stream cert-str))))

(defn make-ssl-ctx [cert-str pkey-str]
  (let [^SSLContext ctx (SSLContext/getInstance "TLS")
        ^KeyStore keystore (KeyStore/getInstance "JKS")
        ^KeyManagerFactory kmf (KeyManagerFactory/getInstance "SunX509")
        ^X509Certificate cert (make-cert cert-str)
        ^RSAPrivateKey k (make-private-key pkey-str)
        chain (into-array Certificate [cert])]
    (.load keystore nil)
    (.setCertificateEntry keystore "cert-alias" cert)
    (.setKeyEntry keystore "key-alias" k (char-array 0) chain)
    (.init kmf keystore (char-array 0))
    (.init ctx (.getKeyManagers kmf) nil nil)
    ctx))

(def valid-compression-types #{:smart :none :deflate})

(defn check-config [config]
  (let [{:keys [certificate-str
                dns-cache-secs
                hostname
                logger
                private-key-str
                ws-on-connect
                ws-on-disconnect
                ws-compression-type]} config]
    (when certificate-str
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` parameter must be a string. "
                     "Got: " certificate-str)
                (u/sym-map certificate-str)))())
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when private-key-str
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:private-key-str` parameter must be a string. "
                     "Got: " private-key-str)
                (u/sym-map private-key-str)))())
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when (and dns-cache-secs (not (integer? dns-cache-secs)))
      (throw (ex-info
              (str "`:dns-cache-secs` parameter must be an integer. Got: `"
                   dns-cache-secs "`.")
              (u/sym-map dns-cache-secs))))
    (when hostname
      (when-not (string? hostname)
        (throw (ex-info
                (str "`:hostname` parameter must be a string. "
                     "Got: " hostname)
                (u/sym-map certificate-str)))())
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when (and logger (not (ifn? logger)))
      (throw (ex-info
              (str "`:logger` option must be a function. Got: `" logger "`.")
              (u/sym-map logger))))
    (when-not ws-on-connect
      (throw (ex-info
              "You must provide a :ws-on-connect fn in the tube-server config."
              config)))
    (when-not (ifn? ws-on-connect)
      (throw (ex-info
              (str "`:ws-on-connect` value must be a function. Got: `"
                   ws-on-connect "`.")
              (u/sym-map ws-on-connect))))
    (when (and ws-on-disconnect (not (ifn? ws-on-disconnect)))
      (throw (ex-info
              (str "`:ws-on-disconnect` value must be a function. Got: `"
                   ws-on-disconnect "`.")
              (u/sym-map ws-on-disconnect))))
    (when (and ws-compression-type
               (not (valid-compression-types ws-compression-type)))
      (throw (ex-info
              (str "`:ws-compression-type` must be one of "
                   valid-compression-types ". Got `" ws-compression-type "`.")
              (u/sym-map ws-compression-type))))))

(defn on-open
  [^WebSocket ws ^ClientHandshake handshake on-connect compression-type
   ws-on-connect *conn-id *conn-id->conn *conn-count]
  (let [fragment-size 65000
        conn-id (swap! *conn-id #(inc (int %)))
        uri (.getResourceDescriptor handshake)
        ^InetSocketAddress remote-sock-addr (.getRemoteSocketAddress ws)
        remote-addr (.getHostAddress
                     ^InetAddress (.getAddress remote-sock-addr))
        sender #(.send ws (bytes %))
        closer #(.close ws %)
        _ (swap! *conn-count #(inc (int %)))
        conn (connection/connection conn-id uri remote-addr on-connect ws
                                    *conn-count sender closer fragment-size
                                    compression-type false)]
    (.setAttachment ws conn-id)
    (swap! *conn-id->conn assoc conn-id conn)
    nil))

(defn tube-server
  ([port config]
   (when-not (int? port)
     (throw (ex-info (str "`port` parameter must be an integer. Got: " port)
                     {:port port})))
   (check-config config)
   (let [{:keys [certificate-str
                 compression-type
                 dns-cache-secs
                 hostname
                 logger
                 private-key-str
                 ws-compression-type
                 ws-on-connect
                 ws-on-disconnect]
          :or {dns-cache-secs 60
               hostname "localhost"
               logger u/println-logger
               ws-compression-type :smart
               ws-on-disconnect (constantly nil)}} config
         ssl-ctx (when (and certificate-str private-key-str)
                   (make-ssl-ctx certificate-str private-key-str))
         *conn-id (atom 0)
         *conn-count (atom 0)
         *conn-id->conn (atom {})
         close-conn! (fn [^WebSocket ws code reason]
                       (let [conn-id (.getAttachment ws)
                             conn (get @*conn-id->conn conn-id)
                             conn-count (swap! *conn-count #(dec (int %)))]
                         (swap! *conn-id->conn dissoc conn-id)
                         (ws-on-disconnect conn code reason conn-count)))
         server (proxy [WebSocketServer] [(InetSocketAddress.
                                           ^String hostname (int port))]
                  (onOpen [ws handshake]
                    (on-open ws handshake ws-on-connect compression-type
                             ws-on-connect *conn-id *conn-id->conn *conn-count))
                  (onClose [ws code reason remote?]
                    (close-conn! ws code reason))
                  (onMessage [^WebSocket ws ^ByteBuffer message]
                    (let [conn-id (.getAttachment ws)
                          conn (get @*conn-id->conn conn-id)]
                      (connection/handle-data conn (.array message))))
                  (onError [ws e]
                    (let [ex-str (u/ex-msg-and-stacktrace e)]
                      (logger :error
                              (str "Got error in tube-server connection:\n"
                                   ex-str))
                      (when ws
                        (close-conn! ws 1011 ex-str))))
                  (onStart []
                    (logger :info (str "Started server on port " port))))
         stop-server (fn stop
                       ([]
                        (stop 0))
                       ([timeout-ms]
                        (.stop ^WebSocketServer server timeout-ms)))]
     (.setReuseAddr ^AbstractWebSocket server true)
     (Security/setProperty "networkaddress.cache.ttl" (str dns-cache-secs))
     (when ssl-ctx
       (.setWebSocketFactory server
                             (DefaultSSLWebSocketServerFactory. ssl-ctx)))
     (logger :info "Starting server...")
     (.start ^WebSocketServer server)
     stop-server)))

;;;;;;;;;;;;;;;;;;;; Test stuff ;;;;;;;;;;;;;;;;;;;;

(defn on-connect [logger conn conn-req conn-count]
  (let [conn-id (connection/get-conn-id conn)
        uri (connection/get-uri conn)
        remote-addr (connection/get-remote-addr conn)
        on-rcv (fn [conn data]
                 (connection/send
                  conn (ba/reverse-byte-array data)))]
    (logger :info (format "Opened conn %s on %s from %s. Conn count: %s"
                          conn-id uri remote-addr conn-count))
    (connection/set-on-rcv! conn on-rcv)))

(defn on-disconnect [logger conn code reason conn-count]
  (let [conn-id (connection/get-conn-id conn)
        uri (connection/get-uri conn)
        remote-addr (connection/get-remote-addr conn)]
    (logger :info (format "Closed conn %s on %s from %s. Conn count: %s"
                          conn-id uri remote-addr conn-count))))

(defn run-normal-test-server
  ([] (run-normal-test-server 8080))
  ([port]
   (let [config {:logger u/println-logger
                 :ws-on-connect (partial on-connect u/println-logger)
                 :ws-on-disconnect (partial on-disconnect u/println-logger)}]
     (tube-server port config))))

(defn run-ssl-test-server
  ([] (run-ssl-test-server 8443))
  ([port]
   (let [config {:certificate-str (slurp (io/resource "server.crt"))
                 :logger u/println-logger
                 :private-key-str (slurp (io/resource "server.key"))
                 :ws-on-connect (partial on-connect u/println-logger)
                 :ws-on-disconnect (partial on-disconnect u/println-logger)}]
     (tube-server port config))))

(defn -main
  [& args]
  (run-normal-test-server)
  (run-ssl-test-server))
